package ru.fediq.scrapingkit.backend

import akka.actor.ActorSystem
import akka.http.scaladsl.model.{HttpMethods, Uri}
import akka.util.ByteString
import com.typesafe.scalalogging.StrictLogging
import redis.api.{Limit, ZaddOption}
import redis.{ByteStringDeserializer, ByteStringSerializer}
import ru.fediq.scrapingkit.backend.RedisHostAwareLinksQueue.PageRefSerialization
import ru.fediq.scrapingkit.model.PageRef
import ru.fediq.scrapingkit.util.Utilities
import spray.json._

import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps
import scala.util.Random

class RedisHostAwareLinksQueue(
  val redisHost: String,
  val redisPort: Int,
  val redisPassword: String,
  val hostname: String
)(
  implicit val system: ActorSystem
) extends LinksQueue with StrictLogging with RedisSupport with PageRefSerialization {

  import RedisHostAwareLinksQueue._

  val allSitesKey = "all"

  def siteUrlsKey(site: String) = "site:" + site

  val clientQueueKey = s"client:$hostname"

  def timeBasedPriority = (System.currentTimeMillis() + Random.nextInt(1000)).toDouble

  def randomPriority = Random.nextDouble()

  var lastSitesScore: Double = 0

  override def redisName = s"queue:$hostname"

  override implicit val dispatcher = Utilities.singleDaemonDispatcher("links-queue")

  override def pull(count: Int): Future[Seq[PageRef]] = {
    val domains = mutable.Set[String]()

    redis
      .zrangebyscoreWithscores[String](allSitesKey, Limit(lastSitesScore, inclusive = false), MaxLimit, Some((0, count)))
      .flatMap { domainsWithScores =>
        domainsWithScores
          .foreach { domainAndScore =>
            domains.add(domainAndScore._1)
          }

        if (domains.size == count) {
          domainsWithScores.lastOption.foreach { domainAndScore =>
            lastSitesScore = domainAndScore._2
          }
          Future.successful(domains)
        } else {
          val remains = count - domains.size
          redis
            .zrangebyscoreWithscores[String](allSitesKey, MinLimit, Limit(lastSitesScore, inclusive = true), Some((0, remains)))
            .map { domainsWithScores2 =>
              domainsWithScores2.foreach { domainAndScore =>
                domains.add(domainAndScore._1)
              }

              domainsWithScores2.lastOption.foreach { domainAndScore =>
                lastSitesScore = domainAndScore._2
              }
              domains
            }
        }
      }
      .flatMap { domains =>
        val result = mutable.ArrayBuffer[PageRef]()
        val futures: Seq[Future[Any]] = domains.toSeq.map { domain =>
          val key = siteUrlsKey(domain)
          redis.zrangebyscore[SerializedPageRef](key, MinLimit, MaxLimit, Some(0, 1)).flatMap { refs =>
            refs
              .headOption
              .map { ref =>
                result += ref.deserialize()
                val addFuture = redis.hset(clientQueueKey, ref.uri.toString, ref)
                val remFuture = redis.zrem(key, ref)
                remFuture.flatMap { _ =>
                  redis.zcard(key).flatMap { size =>
                    if (size == 0) {
                      redis.del(key).flatMap(_ => redis.zrem(allSitesKey, domain))
                    } else {
                      Future.successful()
                    }
                  }
                }
                remFuture.flatMap(_ => addFuture)
              }
              .getOrElse {
                redis.del(key).flatMap(_ => redis.zrem(allSitesKey, domain))
                Future.successful()
              }
          }
        }
        Future.sequence(futures).map(_ => result)
      }
  }

  override def failed(uri: Uri) = commit(uri)

  override def succeed(uri: Uri) = commit(uri)

  override def succeedAll(uris: Seq[Uri]) = batchCommit(uris)

  override def failedAll(uris: Seq[Uri]) = batchCommit(uris)

  override def drownAll(uris: Seq[Uri]): Future[Any] = {
    uris.size match {
      case 0 => Future.successful()
      case 1 => redis
        .hget[SerializedPageRef](clientQueueKey, uris.head.toString)
        .flatMap { found =>
          found.map(ref => enqueue(ref.deserialize())).getOrElse(Future.successful()).flatMap(_ => batchCommit(uris))
        }
      case _ => redis
        .hmget[SerializedPageRef](clientQueueKey, uris.map(_.toString): _*)
        .flatMap { found =>
          enqueueAll(found.flatten.map(_.deserialize())).flatMap(_ => batchCommit(uris))
        }
    }

  }

  override def enqueue(ref: PageRef) = enqueueAll(Seq(ref))

  override def enqueueAll(refs: Seq[PageRef]): Future[Any] = {
    val addByHost: Map[String, Seq[PageRef]] = refs.groupBy(_.uri.authority.host.toString)
    val hostsToAdd: Seq[(Double, String)] = addByHost.keySet.toSeq.map((randomPriority, _))

    val addHostsFuture = redis.zaddWithOptions[String](allSitesKey, Seq(ZaddOption.NX), hostsToAdd: _*)
    val addSitesFuture: Seq[Future[Any]] = addByHost
      .toSeq
      .map { case (host, pageRefs) =>
        val pagesToAdd: Seq[(Double, SerializedPageRef)] = pageRefs.map(ref => (timeBasedPriority * ref.depth, ref.serialize))
        redis.zaddWithOptions(siteUrlsKey(host), Seq(ZaddOption.NX), pagesToAdd: _*)
      }
    addHostsFuture.flatMap(_ => Future.sequence(addSitesFuture))
  }

  override def drown(uri: Uri): Future[Any] = drownAll(Seq(uri))

  protected def commit(uri: Uri): Future[Any] = batchCommit(Seq(uri))

  protected def batchCommit(uris: Seq[Uri]): Future[Any] = {
    if (uris.isEmpty) {
      return Future.successful()
    }
    redis.hdel(clientQueueKey, uris.map(_.toString): _*)
  }
}

object RedisHostAwareLinksQueue {
  val MaxLimit = Limit(Double.PositiveInfinity, inclusive = false)
  val MinLimit = Limit(Double.NegativeInfinity, inclusive = false)

  trait PageRefSerialization extends DefaultJsonProtocol {
    implicit val SerializedPageRefFormat = jsonFormat5(SerializedPageRef)

    implicit val serializer: ByteStringSerializer[SerializedPageRef] = new ByteStringSerializer[SerializedPageRef] {
      override def serialize(data: SerializedPageRef) = ByteString(data.toJson.compactPrint)
    }

    implicit val deserializer: ByteStringDeserializer[SerializedPageRef] = new ByteStringDeserializer[SerializedPageRef] {
      override def deserialize(bs: ByteString) = bs.utf8String.parseJson.convertTo[SerializedPageRef]
    }
  }

  case class SerializedPageRef(
    uri: String,
    method: String,
    scraper: String,
    depth: Int,
    context: Map[String, String]
  ) {
    def deserialize(): PageRef = PageRef(
      Uri(uri),
      HttpMethods.getForKey(method).getOrElse(HttpMethods.GET),
      scraper,
      depth,
      context
    )
  }

  implicit class PageRefWrapper(val ref: PageRef) extends AnyVal {
    def serialize: SerializedPageRef = SerializedPageRef(
      ref.lastUri.toString(),
      ref.method.value,
      ref.scraperName,
      ref.depth,
      ref.context
    )
  }

}