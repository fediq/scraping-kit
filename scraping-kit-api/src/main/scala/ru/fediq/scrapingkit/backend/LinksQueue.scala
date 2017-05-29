package ru.fediq.scrapingkit.backend

import akka.http.scaladsl.model._
import ru.fediq.scrapingkit.model.PageRef
import ru.fediq.scrapingkit.util.{Metrics, Utilities}

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

  def enqueueAll(refs: Seq[PageRef]): Future[Any] = Future.sequence {
    val futures = refs.map(enqueue)
    futures
  }

  def drownAll(uris: Seq[Uri]): Future[Any] =  Future.sequence {
    val futures = uris.map(drown)
    futures
  }

  def failed(uri: Uri): Future[Any]

  def succeed(uri: Uri): Future[Any]

  def enqueue(ref: PageRef): Future[Any]

  def drown(uri: Uri): Future[Any]

  override def close() = {
    // Do nothing
  }
}

class InMemoryLinksQueue(
  priorityFunction: PageRef => Long
)
  extends LinksQueue with Metrics {
  private val queue = mutable.PriorityQueue[(Long, PageRef)]()(Ordering.by(_._1))
  private val pulled = mutable.Map[Uri, PageRef]()

  implicit val dispatcher = Utilities.singleDaemonDispatcher("links-queue")

  private val pulledSizeGauge = metrics.gauge("pulledSize")(pulled.size)
  private val queueSizeGauge = metrics.gauge("queueSize")(queue.size)

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

  override def drown(uri: Uri) = {
    pulled.remove(uri).map(enqueue).getOrElse(Future.successful())
  }

  override def enqueue(ref: PageRef) = Future {
    queue.enqueue((priorityFunction(ref), ref))
  }
}

class InMemoryFifoLinksQueue extends InMemoryLinksQueue(_ => -System.currentTimeMillis())

class InMemoryRandomizedLinksQueue extends InMemoryLinksQueue(_ => Random.nextLong())

