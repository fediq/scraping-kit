package ru.fediq.scrapingkit.backend

import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.pattern.ask
import akka.util.Timeout
import com.github.sstone.amqp.Amqp.QueueParameters
import com.github.sstone.amqp.{Amqp, ChannelOwner, ConnectionOwner}
import com.rabbitmq.client.{ConnectionFactory, GetResponse}
import com.typesafe.scalalogging.StrictLogging
import ru.fediq.scrapingkit.model.PageRef
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.FiniteDuration

class RmqFifoLinksQueue(
  amqpUri: String,
  queueName: String,
  reconnectionDelay: FiniteDuration,
  amqpTimeout: FiniteDuration
)(
  implicit system: ActorSystem
) extends LinksQueue with StrictLogging {

  import RmqFifoLinksQueue._
  implicit val SerializedPageRefFormat = jsonFormat4(SerializedPageRef)

  val currentRequests: mutable.Map[String, Long] = new ConcurrentHashMap[String, Long]().asScala
  implicit val timeout = Timeout(amqpTimeout)

  val factory = new ConnectionFactory()
  factory.setUri(amqpUri)
  val connection = system.actorOf(ConnectionOwner.props(factory, reconnectionDelay))
  val channel = ConnectionOwner.createChildActor(connection, ChannelOwner.props())
  Amqp.waitForConnection(system, connection, channel).await()

  Await.result(channel ? Amqp.DeclareQueue(QueueParameters(
    name = queueName,
    passive = false,
    durable = true,
    exclusive = false,
    autodelete = false
  )), amqpTimeout)

  logger.info(s"Connected to AMQP $amqpUri using queue $queueName")

  override implicit val dispatcher = system.dispatcher

  override def pull(count: Int) = {
    val gets = (1 to count).map { idx =>
      (channel ? Amqp.Get(queue = queueName, autoAck = false)).map {
        case Amqp.Ok(_, None) | Amqp.Ok(_, Some(null)) =>
          Nil
        case Amqp.Ok(_, Some(result: GetResponse)) =>
          val body = new String(result.getBody, StandardCharsets.UTF_8)
          val pageRef = body.parseJson.convertTo[SerializedPageRef].deserialize()
          currentRequests.put(pageRef.uri.toString(), result.getEnvelope.getDeliveryTag)
          Seq(pageRef)
        case Amqp.Error(_, cause) =>
          logger.error("AMQP failure", cause)
          Nil
        case ChannelOwner.NotConnectedError(_) =>
          logger.error("AMPQ channel not connected")
          Nil
      }
    }
    Future.sequence(gets).map(_.flatten)
  }

  override def failed(uri: Uri) = commitQueue(uri)

  override def succeed(uri: Uri) = commitQueue(uri)

  override def drown(uris: Seq[Uri]) = Future.sequence {
    val sequence: Seq[Future[Any]] = uris.map(uri => commitQueue(uri, requeue = true))
    sequence
  }

  override def enqueue(ref: PageRef) = {
    val body = SerializedPageRef(ref.lastUri.toString(), ref.scraperName, ref.depth, ref.context)
      .toJson
      .compactPrint
      .getBytes(StandardCharsets.UTF_8)

    channel ? Amqp.Publish("", queueName, body)
  }

  private def commitQueue(uri: Uri, requeue: Boolean = false): Future[Any] = {
    currentRequests.remove(uri.toString()).map { tag =>
      channel ? (if (!requeue) Amqp.Ack(tag) else Amqp.Reject(tag, requeue = true))
    }.getOrElse {
      logger.warn(s"No running request for $uri")
      Future.successful()
    }
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
    context: Map[String, String]
  ) {
    def deserialize(): PageRef = PageRef(Uri(uri), scraper, depth, context)
  }
}