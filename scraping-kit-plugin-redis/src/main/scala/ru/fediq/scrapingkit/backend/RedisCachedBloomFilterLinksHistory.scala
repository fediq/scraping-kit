package ru.fediq.scrapingkit.backend

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import akka.util.ByteString
import bloomfilter.mutable.BloomFilter
import com.typesafe.scalalogging.StrictLogging
import ru.fediq.scrapingkit.util.Utilities

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.language.postfixOps

class RedisCachedBloomFilterLinksHistory(
  expectedItems: Long,
  falsePositiveRate: Double,
  val redisHost: String,
  val redisPort: Int,
  val redisPassword: String,
  val hostname: String
)(
  implicit val system: ActorSystem
) extends BasicBloomFilterLinksHistory(expectedItems, falsePositiveRate) with RedisSupport with StrictLogging {

  private implicit val dispatcher = Utilities.singleDaemonDispatcher("links-history")

  def randomKey = s"tmp:bloom:${System.currentTimeMillis()}"
  val bitSetKey = "bloom"
  val channelKey = "bloomNews"

  def redisName = s"bloom:$hostname"

  subscribe(channelKey, m => addKnownSync(new String(m.data.toArray)))

  override def addKnown(uri: Uri) = {
    val string = uri.toString()
    addKnownSync(string)
    pushNews(string)
  }

  def readFromRedis: Future[BloomFilter[String]] = {
    redis
      .get[ByteString](bitSetKey)
      .map { maybeByteString =>
        maybeByteString
          .map { byteString =>
            logger.info("Read bloom filter from Redis")
            fromBytes(byteString.toArray)
          }
          .getOrElse{
            logger.warn("No bloom filter ")
            newBloomFilter
          }
      }
  }

  def writeToRedis: Future[Any] = {
    val key = randomKey
    val bytes = toBytes(bloomFilter)
    redis
      .set(key, ByteString(bytes), Some(600))
      .flatMap(_ => redis.bitopOR(bitSetKey, bitSetKey, key))
      .flatMap(_ => redis.del(key))
  }

  def pushNews(uri: String): Future[Long] = {
    redis.publish(channelKey, uri)
  }

  override protected def init = {
    Await.result(readFromRedis, 1 minute)
  }

  override def close() = {
    Await.result(writeToRedis, 1 minute)
  }

  def cleanup() = {
    // TODO remove temporary keys
    // TODO rollback local queues
    // TODO cleanup empty site keys
    // TODO cleanup non-existent sites from "all" list
  }
}
