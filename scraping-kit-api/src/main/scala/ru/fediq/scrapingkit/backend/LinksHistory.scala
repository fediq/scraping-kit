package ru.fediq.scrapingkit.backend

import akka.http.scaladsl.model.Uri
import org.apache.commons.codec.digest.DigestUtils
import ru.fediq.scrapingkit.util.Metrics

import scala.collection.mutable
import scala.concurrent.Future

trait LinksHistory extends AutoCloseable {
  def isKnown(uri: Uri): Future[Boolean]

  def addKnown(uri: Uri): Future[Boolean]

  override def close() = {
    // Do nothin
  }
}

class InMemoryLinksHistory extends LinksHistory with Metrics {
  // TODO handle same urls but different parsers
  private val viewedLinks = mutable.Set[String]()

  private val viewedLinksSizeGauge = metrics.gauge("viewedLinksSize")(viewedLinks.size)

  override def isKnown(uri: Uri) = Future.successful(viewedLinks.contains(hashUri(uri)))

  override def addKnown(uri: Uri) = Future.successful(!viewedLinks.add(hashUri(uri)))

  private def hashUri(uri: Uri): String = DigestUtils.md5Hex(uri.toString())
}



