package ru.fediq.scrap.scraper

import akka.http.scaladsl.model.Uri
import spray.json._

sealed trait Scraped

case class DownloadRequest(uri: Uri, scraperName: String, context: Option[AnyRef] = None) extends Scraped

trait ScrapedEntity extends Scraped {
  def dump: String
}

case class MapScrapedEntity(map: Map[String, Any]) extends ScrapedEntity with DefaultJsonProtocol {
  override def dump = {
    map
      .mapValues {
        case s: String => JsString(s)
        case k: Int => JsNumber(k)
        case k: java.lang.Integer => JsNumber(k)
        case d: Double => JsNumber(d)
        case d: java.lang.Double => JsNumber(d)
        case l: Long => JsNumber(l)
        case l: java.lang.Long => JsNumber(l)
      }
      .mapValues(_.asInstanceOf[JsValue])
      .toJson
      .compactPrint
  }
}

abstract class JsonScrapedEntity[T](format: JsonFormat[T]) extends ScrapedEntity { self: T =>
  override def dump = {
    self.asInstanceOf[T].toJson(format).compactPrint
  }
}

trait PrintableScrapedEntity extends ScrapedEntity {
  override def dump = toString
}
