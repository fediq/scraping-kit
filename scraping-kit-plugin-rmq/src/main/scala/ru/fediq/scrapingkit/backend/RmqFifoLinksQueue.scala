package ru.fediq.scrapingkit.backend

import java.nio.charset.StandardCharsets

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.pattern.ask
import akka.util.Timeout
import com.github.sstone.amqp.Amqp.QueueParameters
import com.github.sstone.amqp.{Amqp, ChannelOwner, ConnectionOwner}
import com.rabbitmq.client.AMQP.Queue.DeclareOk
import com.rabbitmq.client.{ConnectionFactory, GetResponse}
import com.typesafe.scalalogging.StrictLogging
import ru.fediq.scrapingkit.model.PageRef
import ru.fediq.scrapingkit.util.Implicits._
import ru.fediq.scrapingkit.util.Utilities
import spray.json.DefaultJsonProtocol._
import spray.json._

import scala.collection.mutable
import scala.concurrent.duration.{FiniteDuration, _}
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.reflect.{ClassTag, classTag}
import scala.util.{Failure, Success, Try}

class RmqFifoLinksQueue(
  amqpUri: String,
  queueName: String,
  reconnectionDelay: FiniteDuration,
  amqpTimeout: FiniteDuration,
  shuffleDelay: Option[FiniteDuration] = None,
  shuffleBatchSize: Option[Int] = None
)(
  implicit system: ActorSystem
) extends LinksQueue with StrictLogging {

  import RmqFifoLinksQueue._

  implicit val SerializedPageRefFormat = jsonFormat4(SerializedPageRef)
  override implicit val dispatcher = Utilities.singleDaemonDispatcher("links-queue")

  val currentRequests = mutable.Map[String, (Long, PageRef)]()
  implicit val timeout = Timeout(amqpTimeout)

  val factory = new ConnectionFactory()
  factory.setUri(amqpUri)
  val connection = system.actorOf(ConnectionOwner.props(factory, reconnectionDelay))
  val channel = ConnectionOwner.createChildActor(connection, ChannelOwner.props())
  Amqp.waitForConnection(system, connection, channel).await()

  Await.result(askChannel(Amqp.DeclareQueue(QueueParameters(
    name = queueName,
    passive = false,
    durable = true,
    exclusive = false,
    autodelete = false
  ))), amqpTimeout)

  logger.info(s"Connected to AMQP queue $queueName at $amqpUri")

  for (delay <- shuffleDelay; batchSize <- shuffleBatchSize) {
    logger.info(s"Scheduling shuffle every $delay with batches of $batchSize")
    system.scheduler.schedule(0 seconds, delay)(shuffle(batchSize))
  }

  override def drownAll(uris: Seq[Uri]) = uris.chainFutures(drown)

  override def succeedAll(uris: Seq[Uri]) = uris.chainFutures(succeed)

  override def failedAll(uris: Seq[Uri]) = uris.chainFutures(failed)

  override def enqueueAll(refs: Seq[PageRef]) = refs.chainFutures(enqueue)

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

  override def failed(uri: Uri) = commit(uri)

  override def succeed(uri: Uri) = commit(uri)

  override def enqueue(ref: PageRef) = {
    val body = SerializedPageRef(ref.lastUri.toString(), ref.scraperName, ref.depth, ref.context)
      .toJson
      .compactPrint
      .getBytes(StandardCharsets.UTF_8)

    channel ? Amqp.Publish("", queueName, body)
  }

  def drown(uri: Uri): Future[Any] = {
    currentRequests
      .remove(uri.toString())
      .map { case (tag, ref) =>
        askChannel(Amqp.Reject(tag, requeue = false))
          .flatMap(_ => enqueue(ref))
      }
      .getOrElse {
        logger.warn(s"No running request for $uri")
        Future.successful()
      }
  }

  private def commit(uri: Uri): Future[Any] = {
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

  def shuffle(batchSize: Int): Future[Any] = {
    logger.debug("Checking queue size for shuffle")
    val f = askChannelOrFail[DeclareOk](Amqp.DeclareQueue(QueueParameters(name = queueName, passive = true)))
      .map(_.getMessageCount)
      .flatMap { messagesCount =>
        val batchesCount = (messagesCount - 1) / batchSize + 1
        val thisBatchSize = math.min(batchSize, messagesCount)
        logger.info(s"Start shuffling $messagesCount messages using batch $thisBatchSize (total $batchesCount batches)")
        shuffleNext(1, batchesCount, thisBatchSize)
      }
    f.onFailure { case th: Throwable =>
      logger.error("Error in shuffle", th)
    }
    f
  }

  private def shuffleNext(currentBatchNum: Int, batchesCount: Int, batchSize: Int): Future[Any] = {
    logger.debug(s"Shuffle batch $currentBatchNum / $batchesCount")
    val shuffle = shuffleOnce(batchSize, currentBatchNum)
    if (currentBatchNum < batchesCount) {
      shuffle
        .flatMap(_ => shuffleNext(currentBatchNum + 1, batchesCount, batchSize))
    } else {
      shuffle
    }
  }

  private def shuffleOnce(batchSize: Int, currentBatchNum: Int): Future[Any] = {
    val pages: Future[Vector[PageRef]] = (1 to batchSize)
      .foldFutures(Vector.empty[PageRef]) { (vector, idx) =>
        if (idx % 10000 == 0) {
          logger.debug(s"Fetched $idx items to shuffle batch $currentBatchNum")
        }
        askChannelOrIgnore[GetResponse](Amqp.Get(queueName, autoAck = true))
          .map(_.map(pageRefFromResponse).toVector)
          .map(vector ++ _)
      }

    pages
      .flatMap { seq =>
        val shuffled: Seq[PageRef] = scala.util.Random.shuffle(seq)
        logger.debug(s"Enqueueing batch $currentBatchNum")
        enqueueAll(shuffled)
        // TODO proper ack instead of noack
      }
      .map { f =>
        logger.debug(s"Enqueued batch $currentBatchNum")
        f
      }
  }

  private def askChannel[T: ClassTag](query: AnyRef): Future[Try[Option[T]]] = {
    channel ? query map {
      case Amqp.Ok(_, None) => Success(None)
      case Amqp.Ok(_, Some(null)) => Success(None)
      case Amqp.Ok(_, Some(result)) if classTag[T].runtimeClass.isInstance(result) => Success(Some(result.asInstanceOf[T]))
      case Amqp.Ok(_, Some(any)) => Failure(new IllegalStateException(s"Unexpected result class: ${any.getClass}"))
      case Amqp.Error(_, th) => Failure(th)
      case ChannelOwner.NotConnectedError(_) => Failure(new IllegalStateException("AMQP channel not connected"))
    }
  }

  private def askChannelOrFail[T: ClassTag](query: AnyRef): Future[T] = {
    askChannel[T](query) map {
      case Success(Some(result)) => result
      case Success(None) => throw new IllegalStateException("No result from channel")
      case Failure(th) => throw th
    }
  }

  private def askChannelOrIgnore[T: ClassTag](query: AnyRef): Future[Option[T]] = {
    askChannel[T](query) map {
      case Success(Some(result)) => Some(result)
      case Success(None) => None
      case Failure(th) => None
    }
  }

  private def pageRefFromResponse(result: GetResponse): PageRef = {
    new String(result.getBody, StandardCharsets.UTF_8)
      .parseJson
      .convertTo[SerializedPageRef]
      .deserialize()
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