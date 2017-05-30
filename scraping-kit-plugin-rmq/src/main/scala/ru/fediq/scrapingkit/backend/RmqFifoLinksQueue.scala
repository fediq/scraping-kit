package ru.fediq.scrapingkit.backend

import java.nio.charset.StandardCharsets

import akka.actor.ActorSystem
import akka.pattern.ask
import com.github.sstone.amqp.Amqp
import com.github.sstone.amqp.Amqp.QueueParameters
import com.rabbitmq.client.GetResponse
import ru.fediq.scrapingkit.model.PageRef
import spray.json._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.{Failure, Success}

class RmqFifoLinksQueue(
  amqpUri: String,
  queueName: String,
  reconnectionDelay: FiniteDuration,
  amqpTimeout: FiniteDuration
)(
  implicit system: ActorSystem
) extends RmqLinksQueueBase(amqpUri, reconnectionDelay, amqpTimeout) {

  import RmqConstants._

  Await.result(askChannel(Amqp.DeclareQueue(QueueParameters(
    name = queueName,
    passive = false,
    durable = true,
    exclusive = false,
    autodelete = false
  ))), amqpTimeout)

  logger.info(s"Connected to AMQP queue $queueName at $amqpUri")

  override def pull(count: Int) = {
    val gets = (1 to count).map { _ =>
      askChannel[GetResponse](Amqp.Get(queue = queueName, autoAck = false)) map {
        case Success(Some(result)) =>
          val pageRef: PageRef = pageRefFromResponse(result)
          currentRequests.put(pageRef.uri.toString(), (result.getEnvelope.getDeliveryTag, pageRef))
          Seq(pageRef)
        case Success(None) =>
          Nil
        case Failure(th) =>
          logger.error("AMQP failure", th)
          Nil
      }
    }
    Future.sequence(gets).map(_.flatten)
  }

  override def enqueue(ref: PageRef) = {
    val body = SerializedPageRef(ref.lastUri.toString(), ref.scraperName, ref.depth, ref.context)
      .toJson
      .compactPrint
      .getBytes(StandardCharsets.UTF_8)

    channel ? Amqp.Publish("", queueName, body)
  }
}
