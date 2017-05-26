package ru.fediq.scrapingkit

import akka.http.scaladsl.model.{HttpEntity, StatusCode}
import org.joda.time.DateTime
import ru.fediq.scrapingkit.model.PageRef

package object platform {

  implicit class PageRefWrapper(val pageRef: PageRef) extends AnyVal {
    def fail(reason: String) = FailedPage(pageRef, DateTime.now, reason = Some(reason))

    def fail(cause: Throwable) = FailedPage(pageRef, DateTime.now, cause = Some(cause))

    def body(body: HttpEntity.Strict, statusCode: StatusCode) = DownloadedPage(pageRef, DateTime.now, statusCode, body)
  }

}
