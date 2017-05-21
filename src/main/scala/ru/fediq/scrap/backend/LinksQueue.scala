package ru.fediq.scrap.backend

import akka.http.scaladsl.model._
import ru.fediq.scrap.platform.PageRef
import ru.fediq.scrap.platform.QueueingActor.metrics
import ru.fediq.scrap.{Metrics, Utilities}

import scala.collection.mutable
import scala.concurrent.{ExecutionContextExecutor, Future}
import scala.util.Random

trait LinksQueue extends AutoCloseable {
  implicit def dispatcher: ExecutionContextExecutor

  def pull(count: Int = 1): Future[Seq[PageRef]]

  def succeedAll(uris: Seq[Uri]): Future[Any] = Future.sequence {
    val futures: Seq[Future[Any]] = uris.map(succeed)
    futures
  }

  def failedAll(uris: Seq[Uri]): Future[Any] = Future.sequence {
    val futures = uris.map(failed)
    futures
  }

  def failed(uri: Uri): Future[Any]

  def succeed(url: Uri): Future[Any]

  def drown(urls: Seq[Uri]): Future[Any]

  def enqueue(ref: PageRef): Future[Any]

  override def close() = {
    // Do nothing
  }
}

class InMemoryLinksQueue(
  priorityFunction: PageRef => Long
)
  extends LinksQueue with Metrics {
  // TODO handle same urls but different parsers
  private val viewedLinks = mutable.Set[String]()
  private val queue = mutable.PriorityQueue[(Long, PageRef)]()(Ordering.by(_._1))
  private val pulled = mutable.Map[Uri, PageRef]()

  implicit val dispatcher = Utilities.singleDaemonThreadDispatcher("links-queue")

  private val pulledSizeGauge = metrics.gauge("pulledSize")(pulled.size)
  private val queueSizeGauge = metrics.gauge("queueSize")(queue.size)
  private val viewedLinksSizeGauge = metrics.gauge("viewedLinksSize")(viewedLinks.size)

  private val enqueueNewMeter = metrics.meter("enqueueNewRate")
  private val enqueueVisitedMeter = metrics.meter("enqueueVisitedRate")

  override def pull(count: Int) = Future {
    val buf = mutable.ArrayBuffer[PageRef]()
    while (buf.size < count && queue.nonEmpty) {
      val req = queue.dequeue()._2
      buf.append(req)
      pulled.put(req.uri, req)
    }
    buf
  }

  override def succeed(url: Uri) = Future {
    pulled.remove(url)
  }

  override def failed(url: Uri) = Future {
    pulled.remove(url)
  }

  override def drown(urls: Seq[Uri]) = Future {
    val drowned = urls.flatMap(url => pulled.remove(url).toSeq)
    drowned.foreach(enqueue)
  }

  override def enqueue(ref: PageRef) = Future {
    val url = ref.uri.toString()
    if (!viewedLinks.contains(url)) {
      queue.enqueue((priorityFunction(ref), ref))
      viewedLinks.add(url)
      enqueueNewMeter.mark()
    } else {
      enqueueVisitedMeter.mark()
    }
  }
}

class InMemoryFifoLinksQueue extends InMemoryLinksQueue(_ => -System.currentTimeMillis())
class InMemoryRandomizedLinksQueue extends InMemoryLinksQueue(_ => Random.nextLong())
