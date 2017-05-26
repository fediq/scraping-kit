package ru.fediq.scrapingkit.scraper

import akka.http.scaladsl.model.Uri
import spray.json._

sealed trait Scraped

case class DownloadRequest(uri: Uri, scraperName: String, context: Option[Map[String, String]] = None) extends Scraped

trait ScrapedEntity extends Scraped {
  def dump: String
}

case class MapScrapedEntity(map: Map[String, String]) extends ScrapedEntity with DefaultJsonProtocol {
  override def dump = map.toJson.compactPrint
}

abstract class JsonScrapedEntity[T](format: JsonFormat[T]) extends ScrapedEntity {
  self: T =>
  override def dump = {
    self.asInstanceOf[T].toJson(format).compactPrint
  }
}

trait PrintableScrapedEntity extends ScrapedEntity {
  override def dump = toString
}
