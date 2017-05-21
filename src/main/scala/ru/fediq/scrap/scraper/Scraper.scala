package ru.fediq.scrap.scraper

import java.nio.charset.StandardCharsets

import akka.http.scaladsl.model._
import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}

import scala.collection.JavaConverters._
import scala.collection.convert.ToScalaImplicits
import scala.language.implicitConversions
import scala.util.Try

trait Scraper {
  def scrape(uri: Uri, status: StatusCode, body: HttpEntity.Strict, context: Option[AnyRef]): Seq[Scraped]
}

trait HtmlScraper extends Scraper {
  def scrape(document: Document, context: Option[AnyRef]): Seq[Scraped]

  override def scrape(uri: Uri, status: StatusCode, body: HttpEntity.Strict, context: Option[AnyRef]) = {
    (status, body.contentType.mediaType) match {
      case (s: StatusCodes.Success, MediaTypes.`text/html`) =>
        val charset = body.contentType.charsetOption.map(_.nioCharset).getOrElse(StandardCharsets.UTF_8)
        val bodyString = body.data.decodeString(charset)
        val document = Jsoup.parse(bodyString, uri.toString())
        scrape(document, context)

      case (s: StatusCodes.Success, mediaType) =>
        throw new UnsupportedMediaTypeException(mediaType)

      case (s, _) =>
        throw new UnsupportedStatusException(s)
    }
  }
}

class HtmlCrawlingScraper(name: String) extends HtmlScraper with JsoupScrapingHelper {
  override def scrape(document: Document, context: Option[AnyRef]) = {
    document
      .select("a")
      .flatMap(_.maybeHref)
      .map(uri => DownloadRequest(uri, name))
  }
}

trait JsoupScrapingHelper extends ToScalaImplicits {
  implicit class ElementWrapper(val e: Element) {
    def maybeHref: Option[Uri] = {
      Option(e.attr("abs:href"))
        .flatMap { href =>
          Try(Uri(href))
            .toOption
        }
    }
  }
}

class ScrapingException(message: String = null, cause: Throwable = null) extends Exception(message, cause)

class UnsupportedMediaTypeException(mediaType: MediaType) extends ScrapingException(mediaType.value)

class UnsupportedStatusException(status: StatusCode) extends ScrapingException(status.value)
