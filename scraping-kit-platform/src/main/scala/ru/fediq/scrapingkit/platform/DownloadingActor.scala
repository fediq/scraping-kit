package ru.fediq.scrapingkit.platform

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Location
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import ru.fediq.scrapingkit.ScrapingKitConfig
import ru.fediq.scrapingkit.backend._
import ru.fediq.scrapingkit.model.PageRef
import ru.fediq.scrapingkit.util.Metrics

import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class DownloadingActor(
  cache: PageCache,
  config: ScrapingKitConfig,
  redirectFilter: Option[(PageRef, Uri) => Boolean] = None
) extends ScrapingKitActor with Metrics {

  import DownloadingActor._
  import context.dispatcher

  val http = Http(context.system)
  implicit val materializer = ActorMaterializer()

  private val requestsMeter = metrics.meter("requestsRate")
  private val httpErrorsMeter = metrics.meter("httpErrorsRate")
  private val downloadsMeter = metrics.meter("downloadsRate")
  private val cacheHitsMeter = metrics.meter("cacheHitsRate")
  private val cacheMissesMeter = metrics.meter("cacheMissesRate")
  private val cacheRedirectsMeter = metrics.meter("cacheRedirectsRate")
  private val cacheFailuresMeter = metrics.meter("cacheFailuresRate")
  private val downloadedRedirectsMeter = metrics.meter("downloadedRedirectsRate")
  private val suppressedRedirectsMeter = metrics.meter("suppressedRedirectsRate")
  private val downloadedPagesMeter = metrics.meter("downloadedPagesRate")

  private val cacheLoadTimer = metrics.timer("cacheLoadTime")
  private val cacheStoreTimer = metrics.timer("cacheStoreTime")
  private val startResponseTimer = metrics.timer("startResponseTime")
  private val finishResponseTimer = metrics.timer("finishResponseTime")

  override def receive = {
    case PageToDownload(ref) =>
      log.debug(s"Requested download for ${ref.lastUri}")
      requestsMeter.mark()

      self ! ref

    case ref: PageRef =>
      log.debug(s"Downloading ${ref.lastUri}")
      downloadsMeter.mark()

      if (ref.depth > config.maxCrawlingDepth) {
        handleFailure(ref, "Too deep")
      } else if (ref.redirectsChain.size > config.maxCrawlingRedirects) {
        handleFailure(ref, s"Too many redirects: ${ref.redirectsChain}")
      } else if (ref.method != HttpMethods.GET) {
        self ! CacheCheckResult(ref, None) // Only GETs could be cached
      } else {
        cacheLoadTimer.timeFuture(
          cache
            .load(ref.lastUri)
            .map(cached => CacheCheckResult(ref, cached))
            .pipeTo(self)
        )
      }

    case CacheCheckResult(ref, Some(CachedPage(status, time, body))) =>
      log.debug(s"Cache hit for ${ref.lastUri}")
      cacheHitsMeter.mark()

      scrapingActor ! DownloadedPage(ref, time, status, body)

    case CacheCheckResult(ref, Some(CachedRedirect(location, _))) =>
      val normalizedLocation = location.resolvedAgainst(ref.lastUri)
      log.debug(s"Cached redirect for ${ref.lastUri} to $normalizedLocation")
      cacheRedirectsMeter.mark()

      self ! ref.chain(normalizedLocation)

    case CacheCheckResult(ref, Some(CachedFailure(failure, _))) =>
      log.debug(s"Cached failure for ${ref.lastUri}")
      cacheFailuresMeter.mark()

      queueingActor ! ref.fail(failure)

    case CacheCheckResult(ref, None) =>
      log.debug(s"Cache miss for ${ref.lastUri}")
      cacheMissesMeter.mark()

      startResponseTimer.timeFuture(
        http
          .singleRequest(HttpRequest(uri = ref.lastUri, method = ref.method))
          .map(resp => PageRefAndResponse(ref, resp))
          .recover {
            case NonFatal(th) => PageRefAndFailure(ref, th)
          }
          .pipeTo(self)
      )

    case PageRefAndResponse(ref, response) =>
      log.debug(s"Downloaded ${ref.lastUri} (${response.status})")
      response.status match {
        case _: StatusCodes.Redirection =>
          response.header[Location] match {
            case Some(location) =>
              val normalizedLocation = location.uri.resolvedAgainst(ref.lastUri)
              if (redirectFilter.isEmpty || redirectFilter.get.apply(ref, normalizedLocation)) {
                log.debug(s"Redirect from ${ref.lastUri} to $normalizedLocation")
                downloadedRedirectsMeter.mark()
                self ! ref.chain(normalizedLocation)
              } else {
                log.debug(s"Redirect from ${ref.lastUri} to $normalizedLocation suppressed by filter")
                suppressedRedirectsMeter.mark()
                handleFailure(ref, "Redirect suppressed")
              }

            case None =>
              handleFailure(ref, "No location in redirect")
          }
          response.discardEntityBytes()

        case _ =>
          finishResponseTimer.timeFuture(
            response
              .entity
              .withSizeLimit(config.maxPageSize)
              .toStrict(config.downloadTimeout)
          )
            .onComplete {
              case Success(body) =>
                downloadedPagesMeter.mark()

                val page = ref.body(body, response.status)
                scrapingActor ! page
                cacheFetch(ref, page)

              case Failure(th) =>
                log.debug(s"Failed to fetch page ${ref.lastUri} (${th.getMessage})")
                httpErrorsMeter.mark()
                // TODO retry?
                handleFailure(ref, th)
            }
      }

    case PageRefAndFailure(ref, th) =>
      log.debug(s"Failed to download page ${ref.lastUri} (${th.getMessage})")
      httpErrorsMeter.mark()
      // TODO retry?
      handleFailure(ref, th)

    case PipedFailure(th) =>
      log.error(th, "Piped failure")
  }

  private def cacheFetch(ref: PageRef, page: DownloadedPage) = {
    cacheStoreTimer.timeFuture(cache.storeFetch(ref, page.time, page.status, page.body)).pipeFailures
  }

  private def handleFailure(ref: PageRef, th: Throwable) = {
    queueingActor ! ref.fail(th)
    cacheStoreTimer.timeFuture(cache.storeFailure(ref, th.getMessage)).pipeFailures
  }

  private def handleFailure(ref: PageRef, reason: String) = {
    queueingActor ! ref.fail(reason)
    cacheStoreTimer.timeFuture(cache.storeFailure(ref, reason)).pipeFailures
  }
}

object DownloadingActor {

  case class PageRefAndResponse(
    ref: PageRef,
    response: HttpResponse
  )

  case class PageRefAndFailure(
    ref: PageRef,
    failure: Throwable
  )

  case class CacheCheckResult(
    ref: PageRef,
    cached: Option[CachedEntity]
  )

}