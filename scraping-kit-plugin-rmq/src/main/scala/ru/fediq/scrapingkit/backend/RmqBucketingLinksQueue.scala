package ru.fediq.scrapingkit.backend

import java.nio.charset.StandardCharsets

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.pattern.ask
import com.github.sstone.amqp.Amqp
import com.github.sstone.amqp.Amqp.QueueParameters
import com.rabbitmq.client.GetResponse
import ru.fediq.scrapingkit.model.PageRef
import ru.fediq.scrapingkit.util.Implicits._
import spray.json._

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

class RmqBucketingLinksQueue(
  amqpUri: String,
  queuePrefix: String,
  bucketsCount: Int,
  reconnectionDelay: FiniteDuration,
  amqpTimeout: FiniteDuration
)(
  implicit system: ActorSystem
) extends RmqLinksQueueBase(amqpUri, reconnectionDelay, amqpTimeout) {

  import RmqConstants._

  var currentBucket = -1

  Await.result((0 until bucketsCount).chainFutures { idx =>
    askChannel(Amqp.DeclareQueue(QueueParameters(
      name = queueName(idx),
      passive = false,
      durable = true,
      exclusive = false,
      autodelete = false
    )))
  }, amqpTimeout)

  logger.info(s"Connected to $bucketsCount AMQP queues with prefix $queuePrefix at $amqpUri")

  def queueName(idx: Int): String = s"$queuePrefix.$idx"

  def queueName(uri: Uri): String = {
    val hash = uri.authority.host.address().hashCode
    val idx = (hash % bucketsCount + bucketsCount) % bucketsCount
    queueName(idx)
  }

  override def pull(count: Int): Future[Seq[PageRef]] = {
    val buffer = ArrayBuffer[PageRef]()
    val emptyBuckets = mutable.Set[Int]()

    def pullOne: Future[_] = {
      do {
        currentBucket = (currentBucket + 1) % bucketsCount
      } while (emptyBuckets.contains(currentBucket) && emptyBuckets.size < bucketsCount)

      askChannel[GetResponse](Amqp.Get(queue = queueName(currentBucket), autoAck = false)) flatMap {
        case Success(Some(result)) =>
          val pageRef: PageRef = pageRefFromResponse(result)
          currentRequests.put(pageRef.uri.toString(), (result.getEnvelope.getDeliveryTag, pageRef))
          buffer += pageRef
          if (buffer.size < count) pullOne else Future.successful()
        case Success(None) =>
          emptyBuckets += currentBucket
          if (emptyBuckets.size < bucketsCount) pullOne else Future.successful()
        case Failure(th) =>
          logger.error("AMQP failure", th)
          Future.successful()
      }
    }

    pullOne.map(_ => buffer)
  }

  override def enqueue(ref: PageRef) = {
    val body = SerializedPageRef(ref.lastUri.toString(), ref.scraperName, ref.depth, ref.context)
      .toJson
      .compactPrint
      .getBytes(StandardCharsets.UTF_8)

    channel ? Amqp.Publish("", queueName(ref.lastUri), body)
  }
}
