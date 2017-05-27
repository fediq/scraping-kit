package ru.fediq.scrapingkit.backend

import java.io._

import akka.http.scaladsl.model.Uri
import bloomfilter.mutable.BloomFilter
import ru.fediq.scrapingkit.util.{Metrics, Utilities}

import scala.concurrent.Future

class BloomFilterLinksHistory(
  val expectedItems: Long,
  val falsePositiveRate: Float,
  val persistentPath: Option[String] = None
) extends LinksHistory with Metrics {
  val bloomFilter = read()

  override def isKnown(uri: Uri) = Future.successful(bloomFilter.mightContain(uri.toString()))

  override def addKnown(uri: Uri) = Future.successful {
    bloomFilter.add(uri.toString())
    true
  }

  override def close() = {
    persistentPath.foreach { path =>
      Utilities.tryAndClose(new BufferedOutputStream(new FileOutputStream(path)))(bloomFilter.writeTo).get
    }
  }

  private def read() = {
    persistentPath
      .fold {
        BloomFilter[String](expectedItems, falsePositiveRate)
      } { path =>
        if (new File(path).exists()) {
          Utilities.tryAndClose(new BufferedInputStream(new FileInputStream(path)))(BloomFilter.readFrom[String]).get
        } else {
          BloomFilter[String](expectedItems, falsePositiveRate)
        }
      }
  }
}
