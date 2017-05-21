package ru.fediq.scrap.platform

import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Location
import akka.pattern.pipe
import akka.stream.ActorMaterializer
import ru.fediq.scrap.backend._
import ru.fediq.scrap.{ActorScrap, ConfigScrap, Metrics}

import scala.util.control.NonFatal
import scala.util.{Failure, Success}

class DownloadingActor(
  cache: PageCache,
  config: ConfigScrap
) extends ActorScrap with Metrics {

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
  private val downloadedRedirectsMeter = metrics.meter("downloadedRedirectsRate")
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
        queueingActor ! ref.fail(s"Too deep")
      } else if (ref.redirectsChain.size > config.maxCrawlingRedirects) {
        queueingActor ! ref.fail(s"Too many redirects: ${ref.redirectsChain}")
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
      log.debug(s"Cached redirect for ${ref.lastUri}")
      cacheRedirectsMeter.mark()

      self ! ref.chain(location)

    case CacheCheckResult(ref, None) =>
      log.debug(s"Cache miss for ${ref.lastUri}")
      cacheMissesMeter.mark()

      startResponseTimer.timeFuture(
        http
          .singleRequest(HttpRequest(uri = ref.lastUri))
          .map(resp => PageRefAndResponse(ref, resp))
          .recover {
            case NonFatal(th) => PageRefAndFailure(ref, th)
          }
          .pipeTo(self)
      )
    // TODO handle timeouts and failures

    case PageRefAndResponse(ref, response) =>
      log.debug(s"Downloaded ${ref.lastUri} (${response.status})")
      response.status match {
        case _: StatusCodes.Redirection =>
          response.header[Location] match {
            case Some(location) =>
              log.debug(s"Redirect from ${ref.lastUri} to ${location.uri}")
              downloadedRedirectsMeter.mark()

              self ! ref.chain(location.uri)

            case None =>
              queueingActor ! ref.fail("No location in redirect")
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
                cacheStoreTimer.timeFuture(cache.store(page))

              case Failure(th) =>
                log.debug(s"Failed to fetch page ${ref.lastUri} (${th.getMessage})")
                // TODO retry?
                queueingActor ! ref.fail(th)
            }
      }

    case PageRefAndFailure(ref, th) =>
      log.debug(s"Failed to download page ${ref.lastUri} (${th.getMessage})")
      httpErrorsMeter.mark()
      queueingActor ! ref.fail(th)
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