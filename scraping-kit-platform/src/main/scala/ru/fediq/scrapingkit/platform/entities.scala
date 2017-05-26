package ru.fediq.scrapingkit.platform

import akka.http.scaladsl.model.{HttpEntity, StatusCode}
import org.joda.time.DateTime
import ru.fediq.scrapingkit.model.PageRef
import ru.fediq.scrapingkit.scraper.Scraped

case class PageToDownload(
  ref: PageRef
)

case class PageToEnqueue(
  ref: PageRef
)

case class DownloadedPage(
  ref: PageRef,
  time: DateTime,
  status: StatusCode,
  body: HttpEntity.Strict
)

case class ScrapedPage(
  page: DownloadedPage,
  entities: Seq[Scraped]
)

case class ProcessedPage(
  ref: PageRef
)

case class FailedPage(
  ref: PageRef,
  time: DateTime,
  reason: Option[String] = None,
  cause: Option[Throwable] = None
)
