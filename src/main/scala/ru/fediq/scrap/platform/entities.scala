package ru.fediq.scrap.platform

import akka.http.scaladsl.model.{HttpEntity, StatusCode, Uri}
import org.joda.time.DateTime
import ru.fediq.scrap.scraper.Scraped

case class PageRef(
  uri: Uri,
  scraperName: String,
  depth: Int = 1,
  context: Option[AnyRef] = None,
  redirectsChain: List[Uri] = Nil
) {
  def lastUri = redirectsChain.headOption.getOrElse(uri)

  def redirectSteps = if (redirectsChain.isEmpty) Nil else uri :: redirectsChain.tail

  def chain(nextLocation: Uri) = copy(
    redirectsChain = nextLocation :: redirectsChain
  )

  def fail(reason: String) = FailedPage(this, DateTime.now, reason = Some(reason))

  def fail(cause: Throwable) = FailedPage(this, DateTime.now, cause = Some(cause))

  def body(body: HttpEntity.Strict, statusCode: StatusCode) = DownloadedPage(this, DateTime.now, statusCode, body)
}

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
