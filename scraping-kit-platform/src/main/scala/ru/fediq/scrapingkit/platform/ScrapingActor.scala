package ru.fediq.scrapingkit.platform

import ru.fediq.scrapingkit._
import ru.fediq.scrapingkit.backend.FeedExporter
import ru.fediq.scrapingkit.model.PageRef
import ru.fediq.scrapingkit.scraper.{DownloadRequest, ScrapedEntity, Scraper}
import ru.fediq.scrapingkit.util.Metrics

import scala.util.{Failure, Success, Try}

class ScrapingActor(
  scrapers: Map[String, Scraper],
  exporter: FeedExporter,
  config: ScrapingKitConfig
) extends ScrapingKitActor with Metrics {

  import ScrapingActor._
  import context.dispatcher

  override def receive = {
    case DownloadedPage(ref, _, status, body) =>
      scrapingMeter.mark()
      scrapers.get(ref.scraperName) match {
        case None =>
          log.warning(s"Unknown scraper '${ref.scraperName}' for ${ref.lastUri}")
          queueingActor ! ref.fail("Unknown scraper")

        case Some(scraper) =>
          log.debug(s"Scraping ${ref.scraperName} at ${ref.lastUri}")
          Try {
            parseTimer.time {
              scraper.scrape(ref.lastUri, status, body, ref.context)
            }
          } match {
            case Failure(th) =>
              log.debug(s"Failed to scrape page ${ref.lastUri}")
              queueingActor ! ref.fail(th)

            case Success(scraped) =>
              val downloadRequests = scraped
                .flatMap {
                  case req: DownloadRequest =>
                    val cleanUri = req.uri.withoutFragment
                    if (scrapers.contains(req.scraperName) && cleanUri.authority.nonEmpty && req.depthInc > 0) {
                      Seq(req.copy(uri = cleanUri))
                    } else {
                      Nil
                    }
                  case _ => Nil
                }
                .toSet

              // TODO warn about same uris with different scrapers

              // TODO filter urls

              if (downloadRequests.nonEmpty) {
                downloadRequests.foreach { req =>
                  val newDepth = ref.depth + req.depthInc
                  if (newDepth <= config.maxCrawlingDepth) {
                    log.debug(s"Scraped download request from ${ref.lastUri} to ${req.uri}")
                    queueingActor ! PageToEnqueue(PageRef(req.uri, req.method, req.scraperName, newDepth, req.context))
                  } else {
                    log.debug(s"Request from ${ref.lastUri} to ${req.uri} will be skipped")
                  }
                }
              }

              scraped.foreach {
                case entity: ScrapedEntity =>
                  // TODO filter results
                  Try {
                    storeTimer.timeFuture(exporter.store(entity))
                    storeMeter.mark()
                  }.failed.foreach { th =>
                    log.error(th, "Failed to store results")
                  }

                case _ => // Noop
              }

              queueingActor ! ProcessedPage(ref)
          }
      }
  }
}

object ScrapingActor extends AnyRef with Metrics {
  private[ScrapingActor] val scrapingMeter = metrics.meter("scrapingRate")
  private[ScrapingActor] val storeMeter = metrics.meter("storeRate")
  private[ScrapingActor] val storeTimer = metrics.timer("storeTime")
  private[ScrapingActor] val parseTimer = metrics.timer("parseTime")
}
