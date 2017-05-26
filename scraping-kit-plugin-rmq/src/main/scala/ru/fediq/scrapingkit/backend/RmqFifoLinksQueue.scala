package ru.fediq.scrapingkit.backend

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.pattern.ask
import com.github.sstone.amqp.{Amqp, ChannelOwner, ConnectionOwner}
import com.rabbitmq.client.{ConnectionFactory, GetResponse}
import ru.fediq.scrapingkit.model.PageRef
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.JavaConverters._
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration

class RmqFifoLinksQueue(
  amqpUri: String,
  queueName: String,
  reconnectionDelay: FiniteDuration
)(
  implicit system: ActorSystem
) extends LinksQueue {

  import RmqFifoLinksQueue._

  val factory = new ConnectionFactory()
  factory.setUri(amqpUri)

  val currentRequests = new ConcurrentHashMap[Uri, Long]().asScala

  val connection = system.actorOf(ConnectionOwner.props(factory, reconnectionDelay))

  val channel = ConnectionOwner.createChildActor(connection, ChannelOwner.props())

  override implicit def dispatcher = system.dispatcher

  override def pull(count: Int) = {
    val gets = (1 to count).map { idx =>
      (channel ? Amqp.Get(queue = queueName, autoAck = false)).map {
        case Amqp.Ok(_, Some(result: GetResponse)) =>
          val body = new String(result.getBody, StandardCharsets.UTF_8)
          val pageRef = body.parseJson.convertTo[SerializedPageRef].deserialize
          currentRequests.put(pageRef.uri, result.getEnvelope.getDeliveryTag)
          Seq(pageRef)
        case Amqp.Error(_, cause) =>
          system.log.error(cause, "AMQP failure")
          Nil
      }
    }
    Future.sequence(gets).map(_.flatten)
  }

  override def failed(uri: Uri) = commitQueue(uri)

  override def succeed(uri: Uri) = commitQueue(uri)

  override def drown(uris: Seq[Uri]) = Future.sequence(uris.map(uri => commitQueue(uri, requeue = true)))

  override def enqueue(ref: PageRef) = {
    val body = SerializedPageRef(ref.lastUri.toString(), ref.scraperName, ref.depth, ref.context)
      .toJson
      .compactPrint
      .getBytes(StandardCharsets.UTF_8)

    channel ? Amqp.Publish("", queueName, body)
  }

  private def commitQueue(uri: Uri, requeue: Boolean = false): Future[Any] = {
    currentRequests.remove(uri).map { tag =>
      channel ? (if (!requeue) Amqp.Ack(tag) else Amqp.Reject(tag, requeue = true))
    }.getOrElse(Future.successful())
  }
}

object RmqFifoLinksQueue {
  val connectorActorName = "links-queue"
  val channelActorName = "rmqchannel"
  val channelActorPath = "/user/links-queue/rmqchannel"

  case class SerializedPageRef(
    uri: String,
    scraper: String,
    depth: Int,
    context: Option[Map[String, String]]
  ) {
    def deserialize(): PageRef = PageRef(Uri(uri), scraper, depth, context)
  }

  implicit val SerializedPageRefFormat = jsonFormat4(SerializedPageRef)

}