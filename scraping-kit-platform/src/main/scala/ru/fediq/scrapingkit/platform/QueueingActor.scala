package ru.fediq.scrapingkit.platform

import akka.http.scaladsl.model._
import akka.pattern.pipe
import ru.fediq.scrapingkit._
import ru.fediq.scrapingkit.backend.{LinksHistory, LinksQueue}
import ru.fediq.scrapingkit.model.PageRef
import ru.fediq.scrapingkit.util.Metrics

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.language.postfixOps

class QueueingActor(
  queue: LinksQueue,
  history: LinksHistory,
  config: ScrapingKitConfig
) extends ScrapingKitActor {

  import QueueingActor._
  import context.dispatcher

  private val freeSlotsGauge = metrics.gauge("freeSlots")(freeSlots)
  private val busySlotsGauge = metrics.gauge("busySlots")(busySlots)
  private val domainSlotsGauge = metrics.gauge("domainSlots")(domainSlots)
  private val runningPullsGauge = metrics.gauge("runningPulls")(pullSlots)

  private val outdatedMeter = metrics.meter("outdatedRate")
  private val pulledMeter = metrics.meter("pulledRate")
  private val pullerIsBusyMeter = metrics.meter("pullerIsBusyRate")
  private val pullTimeoutMeter = metrics.meter("pullTimeoutRate")
  private val succeedMeter = metrics.meter("succeedRate")
  private val failedMeter = metrics.meter("failedRate")
  private val enqueueMeter = metrics.meter("enqueueRate")
  private val enqueueNewMeter = metrics.meter("enqueueNewRate")
  private val enqueueVisitedMeter = metrics.meter("enqueueVisitedRate")

  private val queueFailedAllTimer = metrics.timer("failedAllTime")
  private val queueFailedTimer = metrics.timer("failedTime")
  private val queueSucceedTimer = metrics.timer("succeedTime")
  private val queueEnqueueTimer = metrics.timer("enqueueTime")
  private val queuePullTimer = metrics.timer("pullTime")
  private val historyIsKnownTimer = metrics.timer("isKnownTime")
  private val historyAddKnownTimer = metrics.timer("addKnownTime")

  private val runningRequests = mutable.Map[String, mutable.Map[Uri, Deadline]]()

  private val runningPulls = mutable.Set[Int]()
  private var nextPullId: Int = 0

  private var errorsCount: Int = 0

  override def preStart() = {
    context.system.scheduler.schedule(0 seconds, config.pullingInterval, self, TimeToPull)
  }

  def busySlots: Int = runningRequests.values.foldLeft(0)(_ + _.size)

  def freeSlots: Int = math.max(0, config.maxConcurrentRequests - busySlots)

  def domainSlots: Int = runningRequests.size

  def pullSlots = runningPulls.size

  def addRunning(ref: PageRef): Unit = {
    val domain = ref.uri.authority.host.address()
    val requestsForDomain = runningRequests.getOrElseUpdate(domain, mutable.Map())
    requestsForDomain.put(ref.uri, System.currentTimeMillis() + config.processingTimeout.toMillis)
  }

  def clearOutdated(): Seq[Uri] = {
    val now = System.currentTimeMillis()

    runningRequests
      .keys
      .toSeq
      .flatMap { domain =>
        val requests = runningRequests(domain)

        val outdatedUris = requests
          .filter { case (_, deadline) => deadline <= now }
          .map { case (uri, _) => uri }

        outdatedUris.foreach(requests.remove)

        if (requests.isEmpty) {
          runningRequests.remove(domain)
        }

        outdatedUris
      }
  }

  def remove(uri: Uri) = {
    val domain = uri.authority.host.address()
    runningRequests.get(domain).foreach { requestsForDomain =>
      requestsForDomain.remove(uri)
      if (requestsForDomain.isEmpty) {
        runningRequests.remove(domain)
      }
    }
  }

  override def receive = {
    case TimeToPull =>
      if (runningPulls.size >= config.maxPullsInParallel) {
        log.warning("Time to pull, but puller is busy")
        pullerIsBusyMeter.mark()
      } else {
        log.debug("Time to pull!")
        val outdated = clearOutdated()
        if (outdated.nonEmpty) {
          log.warning(s"${outdated.size} downloads timed out")
          outdated.foreach(uri => log.debug(s"Download timed out: $uri"))
          queueFailedAllTimer.timeFuture(queue.failedAll(outdated)).pipeFailures
          outdatedMeter.mark(outdated.size)
        }

        val free = freeSlots
        if (free > 0) {
          nextPullId += 1
          val pullId = nextPullId
          runningPulls.add(pullId)
          log.info(s"Pulling $free items (#$pullId)")
          queuePullTimer
            .timeFuture(queue.pull(free))
            .map(refs => PulledBatch(refs, free, pullId))
            .pipeTo(self)
          context.system.scheduler.scheduleOnce(config.pullingTimeout, self, PullTimedOut(pullId))
        } else {
          log.debug("No free slots")
        }
      }

    case PulledBatch(refs, free, id) =>
      pulledMeter.mark(refs.size)
      runningPulls.remove(id)
      log.info(s"Wanted $free, pulled ${refs.size} (#$id)")

      refs.foreach { req =>
        addRunning(req)
        downloadingActor ! req
      }

    case PullTimedOut(id) =>
      if (runningPulls.contains(id)) {
        log.warning(s"Pull timed out (#$id)")
        runningPulls.remove(id)
        pullTimeoutMeter.mark()
      }

    case PageToEnqueue(ref) =>
      log.debug(s"Enqueue page request for ${ref.uri}")
      enqueueMeter.mark()

      historyIsKnownTimer
        .timeFuture(history.isKnown(ref.uri))
        .flatMap {
          case false =>
            enqueueNewMeter.mark()
            queueEnqueueTimer
              .timeFuture(queue.enqueue(ref))
              .flatMap(_ => historyAddKnownTimer
                .timeFuture(history.addKnown(ref.uri)))

          case true =>
            enqueueVisitedMeter.mark()
            Future.successful(false)
        }
        .pipeFailures

    case ProcessedPage(ref) =>
      log.debug(s"Processing completed for ${ref.uri}")
      succeedMeter.mark()

      remove(ref.uri)
      queueSucceedTimer.timeFuture(queue.succeed(ref.uri)).pipeFailures

    case FailedPage(ref, _, reason, cause) =>
      val causeString = cause.map(c => s" (${c.getMessage})").getOrElse("")
      val reasonString = reason.map(r => s" ($r)").getOrElse("")

      log.debug(s"Processing failed for ${ref.uri}$reasonString$causeString")
      failedMeter.mark()

      errorsCount += 1
      if (config.logErrorsEach > 0 && errorsCount % config.logErrorsEach == 0) {
        log.info(s"Already failed $errorsCount pages, last for ${ref.uri}$reasonString$causeString")
      }

      remove(ref.uri)
      queueFailedTimer.timeFuture(queue.failed(ref.uri)).pipeFailures

    case PipedFailure(th) =>
      log.error(th, "Piped failure")
  }
}

object QueueingActor extends Metrics {
  type Deadline = Long

  case object TimeToPull

  case class PullTimedOut(id: Int)

  case class PulledBatch(refs: Seq[PageRef], free: Int, id: Int)

}