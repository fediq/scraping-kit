package ru.fediq.scrapingkit.scraper

import java.nio.charset.StandardCharsets

import akka.http.scaladsl.model._
import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}

import scala.collection.convert.WrapAsScala
import scala.language.implicitConversions
import scala.util.Try

trait Scraper {
  def scrape(uri: Uri, status: StatusCode, body: HttpEntity.Strict, context: Map[String, String]): Seq[Scraped]
}

trait HtmlScraper extends Scraper with JsoupScrapingHelper {
  def scrape(uri: Uri, document: Document, context: Map[String, String]): Seq[Scraped]

  def postProcess(scraped: Seq[Scraped], uri: Uri, statusCode: StatusCode, context: Map[String, String]): Seq[Scraped] = scraped

  override def scrape(uri: Uri, status: StatusCode, body: HttpEntity.Strict, context: Map[String, String]) = {
    (status, body.contentType.mediaType) match {
      case (s: StatusCodes.Success, MediaTypes.`text/html`) =>
        val charset = body.contentType.charsetOption.map(_.nioCharset).getOrElse(StandardCharsets.UTF_8)
        val bodyString = body.data.decodeString(charset)
        val document = Jsoup.parse(bodyString, uri.toString())
        val scraped = scrape(uri, document, context)
        postProcess(scraped, uri, status, context)

      case (s: StatusCodes.Success, mediaType) =>
        throw new UnsupportedMediaTypeException(mediaType)

      case (s, _) =>
        throw new UnsupportedStatusException(s)
    }
  }
}

class HtmlCrawlingScraper(name: String) extends HtmlScraper {
  override def scrape(uri: Uri, document: Document, context: Map[String, String]) = {
    document
      .select("a")
      .flatMap(_.maybeHref)
      .map(uri => DownloadRequest(uri, name, context))
  }
}

trait JsoupScrapingHelper extends WrapAsScala {

  implicit class ElementWrapper(val e: Element) {
    def maybeHref: Option[Uri] = {
      Option(e.absUrl("href"))
        .flatMap(tryParseUri)
    }

    def maybeFormAction: Option[Uri] = {
      Option(e.absUrl("action"))
        .flatMap(tryParseUri)
    }

    def maybeFormMethod: Option[HttpMethod] = {
      Option(e.attr("method"))
        .flatMap(method => HttpMethods.getForKey(method.trim.toUpperCase))
    }
  }

  def tryParseUri(s: String) = Try(Uri(s)).toOption
}

class ScrapingException(message: String = null, cause: Throwable = null) extends Exception(message, cause)

class UnsupportedMediaTypeException(mediaType: MediaType) extends ScrapingException(mediaType.value)

class UnsupportedStatusException(status: StatusCode) extends ScrapingException(status.value)
