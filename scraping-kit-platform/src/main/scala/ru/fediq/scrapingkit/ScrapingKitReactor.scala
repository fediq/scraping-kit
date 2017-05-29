package ru.fediq.scrapingkit

import akka.actor.{ActorSystem, Props}
import akka.routing.RoundRobinPool
import com.typesafe.scalalogging.StrictLogging
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import ru.fediq.scrapingkit.backend.{FeedExporter, LinksHistory, LinksQueue, PageCache}
import ru.fediq.scrapingkit.platform._
import ru.fediq.scrapingkit.scraper.Scraper

import scala.util.Try

class ScrapingKitReactor(
  linksQueue: LinksQueue,
  linksHistory: LinksHistory,
  pageCache: PageCache,
  exporter: FeedExporter,
  scrapers: Map[String, Scraper]
)(implicit val system: ActorSystem)
  extends AnyRef with AutoCloseable with StrictLogging {

  val config = system.settings.config.as[ScrapingKitConfig]("scrapingkit")

  val queueingActor = system
    .actorOf(
      Props(new QueueingActor(linksQueue, linksHistory, config))
        .withDispatcher("pinnedDispatcher"),
      "queueing"
    )

  val downloadingActor = system
    .actorOf(
      Props(new DownloadingActor(pageCache, config)),
      "downloading"
    )

  val scrapingActor = system
    .actorOf(
      RoundRobinPool(config.scrapingThreads, routerDispatcher = "pinnedDispatcher")
        .props(Props(new ScrapingActor(scrapers, exporter, config))),
      "scraping"
    )

  system.registerOnTermination(close())

  override def close() = {
    logger.info("Stopping ScarpingKit Reactor")
    Try(linksQueue.close())
    Try(linksHistory.close())
    Try(pageCache.close())
    Try(exporter.close())
    logger.info("Stopped")
  }
}
