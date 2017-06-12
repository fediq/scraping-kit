package ru.fediq.scrapingkit.backend

import java.nio.charset.StandardCharsets

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.pattern.ask
import akka.util.Timeout
import com.github.sstone.amqp.{Amqp, ChannelOwner, ConnectionOwner}
import com.rabbitmq.client.{ConnectionFactory, GetResponse}
import com.typesafe.scalalogging.StrictLogging
import ru.fediq.scrapingkit.backend.RmqConstants.SerializedPageRef
import ru.fediq.scrapingkit.model.PageRef
import ru.fediq.scrapingkit.util.Implicits._
import ru.fediq.scrapingkit.util.Utilities
import spray.json.DefaultJsonProtocol.{jsonFormat4, _}
import spray.json._

import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.reflect.{ClassTag, classTag}
import scala.util.{Failure, Success, Try}

abstract class RmqLinksQueueBase(
  amqpUri: String,
  reconnectionDelay: FiniteDuration,
  amqpTimeout: FiniteDuration
)(
  implicit system: ActorSystem
) extends LinksQueue with StrictLogging {

  implicit val timeout = Timeout(amqpTimeout)

  val factory = {
    val f = new ConnectionFactory()
    f.setUri(amqpUri)
    f
  }

  val connection = system.actorOf(ConnectionOwner.props(factory, reconnectionDelay))

  val channel = {
    val c = ConnectionOwner.createChildActor(connection, ChannelOwner.props())
    Amqp.waitForConnection(system, c).await()
    c
  }

  implicit val SerializedPageRefFormat = jsonFormat4(SerializedPageRef)

  override implicit val dispatcher = Utilities.singleDaemonDispatcher("links-queue")

  val currentRequests = mutable.Map[String, (Long, PageRef)]()

  override def drownAll(uris: Seq[Uri]) = uris.chainFutures(drown)

  override def succeedAll(uris: Seq[Uri]) = uris.chainFutures(succeed)

  override def failedAll(uris: Seq[Uri]) = uris.chainFutures(failed)

  override def enqueueAll(refs: Seq[PageRef]) = refs.chainFutures(enqueue)

  override def failed(uri: Uri) = commit(uri)

  override def succeed(uri: Uri) = commit(uri)

  def drown(uri: Uri): Future[Any] = {
    currentRequests
      .remove(uri.toString())
      .map { case (tag, ref) =>
        askChannel(Amqp.Ack(tag))
          .flatMap(_ => enqueue(ref))
      }
      .getOrElse {
        logger.warn(s"No running request for $uri")
        Future.successful()
      }
  }

  protected def commit(uri: Uri): Future[Any] = {
    currentRequests
      .remove(uri.toString())
      .map { case (tag, _) =>
        channel ? Amqp.Ack(tag)
      }
      .getOrElse {
        logger.warn(s"No running request for $uri")
        Future.successful()
      }
  }

  protected def askChannel[T: ClassTag](query: AnyRef): Future[Try[Option[T]]] = {
    channel ? query map {
      case Amqp.Ok(_, None) => Success(None)
      case Amqp.Ok(_, Some(null)) => Success(None)
      case Amqp.Ok(_, Some(result)) if classTag[T].runtimeClass.isInstance(result) => Success(Some(result.asInstanceOf[T]))
      case Amqp.Ok(_, Some(any)) => Failure(new IllegalStateException(s"Unexpected result class: ${any.getClass}"))
      case Amqp.Error(_, th) => Failure(th)
      case ChannelOwner.NotConnectedError(_) => Failure(new IllegalStateException("AMQP channel not connected"))
    }
  }

  protected def pageRefFromResponse(result: GetResponse): PageRef = {
    new String(result.getBody, StandardCharsets.UTF_8)
      .parseJson
      .convertTo[SerializedPageRef]
      .deserialize()
  }
}
