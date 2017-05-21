package ru.fediq.scrap.platform

import akka.http.scaladsl.model._
import akka.pattern.pipe
import ru.fediq.scrap._
import ru.fediq.scrap.backend.LinksQueue

import scala.collection.mutable
import scala.concurrent.duration._
import scala.language.postfixOps

class QueueingActor(
  queue: LinksQueue,
  config: ConfigScrap
) extends ActorScrap {

  import QueueingActor._
  import context.dispatcher

  private val freeSlotsGauge = metrics.gauge("freeSlots")(freeSlots)
  private val busySlotsGauge = metrics.gauge("busySlots")(busySlots)
  private val domainSlotsGauge = metrics.gauge("domainSlots")(domainSlots)

  private val outdatedMeter = metrics.meter("outdatedRate")
  private val pulledMeter = metrics.meter("pulledRate")
  private val succeedMeter = metrics.meter("succeedRate")
  private val failedMeter = metrics.meter("failedRate")
  private val drownMeter = metrics.meter("drownRate")
  private val runningMeter = metrics.meter("runningRate")
  private val enqueueMeter = metrics.meter("enqueueRate")
  private val drownShareHist = metrics.histogram("drownShare")

  private val queueFailedAllTimer = metrics.timer("failedAllTime")
  private val queueFailedTimer = metrics.timer("failedTime")
  private val queueSucceedTimer = metrics.timer("succeedTime")
  private val queueEnqueueTimer = metrics.timer("enqueueTime")
  private val queueDrownTimer = metrics.timer("drownTime")
  private val queuePullTimer = metrics.timer("pullTime")

  val runningRequests = mutable.Map[String, mutable.Map[Uri, Deadline]]()

  override def preStart() = {
    context.system.scheduler.schedule(0 seconds, config.pullingInterval, self, TimeToPull)
  }

  def busySlots: Int = runningRequests.values.foldLeft(0)(_ + _.size)

  def freeSlots: Int = config.maxConcurrentRequests - busySlots

  def domainSlots: Int = runningRequests.size

  def tryAdd(ref: PageRef): Boolean = {
    val domain = ref.uri.authority.host.address()
    val numRequestsForDomain = runningRequests.get(domain).map(_.size).getOrElse(0)
    if (numRequestsForDomain >= config.maxConcurrentRequestsPerDomain) {
      false
    } else {
      val requestsForDomain = runningRequests.getOrElseUpdate(domain, mutable.Map())
      requestsForDomain.put(ref.uri, System.currentTimeMillis() + config.processingTimeout.toMillis)
      true
    }
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
      log.debug("Time to pull!")
      val outdated = clearOutdated()
      if (outdated.nonEmpty) {
        log.warning(s"${outdated.size} downloads timed out")
        outdated.foreach(uri => log.debug(s"Download timed out: $uri"))
        queueFailedAllTimer.timeFuture(queue.failedAll(outdated))
        outdatedMeter.mark(outdated.size)
      }

      val free = freeSlots
      if (free > 0) {
        val toPull = (free * (100.0 / (100.0 - drownShareHist.mean))).toInt
        log.debug(s"Want $free items, pulling $toPull")
        queuePullTimer
          .timeFuture(queue.pull(toPull))
          .map(refs => PulledBatch(refs, free))
          .pipeTo(self)
      }

    case PulledBatch(refs, free) =>
      pulledMeter.mark(refs.size)
      val (toRun, toDrown) = refs.partition(tryAdd)
      log.info(s"Pulled ${refs.size} items, wanted $free, downloading ${toRun.size}, drowning ${toDrown.size}")
      drownShareHist += (if (refs.nonEmpty) 100 * toDrown.size / refs.size else 0)

      runningMeter.mark(toRun.size)
      toRun.foreach(req => downloadingActor ! req)

      drownMeter.mark(toDrown.size)
      queueDrownTimer.timeFuture(queue.drown(toDrown.map(_.uri)))

    case PageToEnqueue(ref) =>
      log.debug(s"Enqueue page request for ${ref.uri}")
      enqueueMeter.mark()

      queueEnqueueTimer.timeFuture(queue.enqueue(ref))

    case ProcessedPage(ref) =>
      log.debug(s"Processing completed for ${ref.uri}")
      succeedMeter.mark()

      remove(ref.uri)
      queueSucceedTimer.timeFuture(queue.succeed(ref.uri))

    case FailedPage(ref, _, reason, cause) =>
      val causeString = cause.map(c => s"(${c.getMessage}) ").getOrElse("")
      val reasonString = reason.map(r => s"($r) ").getOrElse("")
      log.info(s"Processing failed $reasonString${causeString}for ${ref.uri}")
      failedMeter.mark()

      remove(ref.uri)
      queueFailedTimer.timeFuture(queue.failed(ref.uri))
  }
}

object QueueingActor extends Metrics {
  type Deadline = Long

  case object TimeToPull

  case class PulledBatch(refs: Seq[PageRef], free: Int)

}