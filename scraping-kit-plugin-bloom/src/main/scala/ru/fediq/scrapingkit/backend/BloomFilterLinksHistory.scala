package ru.fediq.scrapingkit.backend

import java.io._

import akka.http.scaladsl.model.Uri
import bloomfilter.mutable.BloomFilter
import com.typesafe.scalalogging.StrictLogging
import ru.fediq.scrapingkit.util.{Metrics, Utilities}

import scala.concurrent.Future

class BloomFilterLinksHistory(
  val expectedItems: Long,
  val falsePositiveRate: Double,
  val persistentPath: Option[String] = None
) extends LinksHistory with Metrics with StrictLogging {
  val bloomFilter = read()

  override def isKnown(uri: Uri) = Future.successful(bloomFilter.mightContain(uri.toString()))

  override def addKnown(uri: Uri) = Future.successful {
    bloomFilter.add(uri.toString())
    true
  }

  override def close() = {
    persistentPath.foreach { path =>
      logger.info(s"Persisting history filter to $path")
      Utilities.tryAndClose(new BufferedOutputStream(new FileOutputStream(path)))(bloomFilter.writeTo).get
    }
  }

  private def read() = {
    persistentPath
      .fold {
        logger.warn("History filter persistence disabled")
        BloomFilter[String](expectedItems, falsePositiveRate)
      } { path =>
        if (new File(path).exists()) {
          logger.info(s"Reading persisted history filter from $path")
          Utilities.tryAndClose(new BufferedInputStream(new FileInputStream(path)))(BloomFilter.readFrom[String]).get
        } else {
          logger.info(s"No persisted history found at $path - initializing empty filter")
          BloomFilter[String](expectedItems, falsePositiveRate)
        }
      }
  }
}
