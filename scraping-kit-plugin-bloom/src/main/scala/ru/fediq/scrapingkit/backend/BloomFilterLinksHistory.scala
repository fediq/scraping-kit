package ru.fediq.scrapingkit.backend

import java.io._

import akka.http.scaladsl.model.Uri
import bloomfilter.mutable.BloomFilter
import com.typesafe.scalalogging.StrictLogging
import ru.fediq.scrapingkit.util.{Metrics, Utilities}

import scala.concurrent.Future


class BasicBloomFilterLinksHistory(
  val expectedItems: Long,
  val falsePositiveRate: Double
) extends LinksHistory {
  lazy val bloomFilter: BloomFilter[String] = init

  protected def init: BloomFilter[String] = newBloomFilter

  protected def newBloomFilter: BloomFilter[String] = BloomFilter[String](expectedItems, falsePositiveRate)

  override def isKnown(uri: Uri): Future[Boolean] = Future.successful {
    isKnownSync(uri.toString())
  }

  override def addKnown(uri: Uri): Future[Any] = Future.successful {
    addKnownSync(uri.toString())
  }

  def isKnownSync(uri: String) = bloomFilter.mightContain(uri)

  def addKnownSync(uri: String) = bloomFilter.add(uri)

  def fromBytes(bytes: Array[Byte]) = {
    BloomFilter.readFrom[String](new ByteArrayInputStream(bytes))
  }

  def toBytes(bf: BloomFilter[String]): Array[Byte] = {
    val os = new ByteArrayOutputStream()
    bf.writeTo(os)
    os.toByteArray
  }
}

class BloomFilterLinksHistory(
  expectedItems: Long,
  falsePositiveRate: Double,
  val persistentPath: Option[String] = None
) extends BasicBloomFilterLinksHistory(expectedItems, falsePositiveRate) with Metrics with StrictLogging {

  override def close() = {
    persistentPath.foreach { path =>
      logger.info(s"Persisting history filter to $path")
      Utilities.tryAndClose(new BufferedOutputStream(new FileOutputStream(path)))(bloomFilter.writeTo).get
    }
  }

  override protected def init = {
    persistentPath
      .fold {
        logger.warn("History filter persistence disabled")
        newBloomFilter
      } { path =>
        if (new File(path).exists()) {
          logger.info(s"Reading persisted history filter from $path")
          Utilities.tryAndClose(new BufferedInputStream(new FileInputStream(path)))(BloomFilter.readFrom[String]).get
        } else {
          logger.info(s"No persisted history found at $path - initializing empty filter")
          newBloomFilter
        }
      }
  }
}
