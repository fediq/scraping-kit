package ru.fediq.scrap

import akka.actor.{Actor, ActorLogging, ActorSystem, Props}
import akka.routing.RoundRobinPool
import com.typesafe.config.Config
import ru.fediq.scrap.backend.{FeedExporter, LinksQueue, PageCache}
import ru.fediq.scrap.platform._
import ru.fediq.scrap.scraper.Scraper
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

class ReactorScrap(
  coreConfig: Config,
  linksQueue: LinksQueue,
  pageCache: PageCache,
  exporter: FeedExporter,
  scrapers: Map[String, Scraper]
) extends AnyRef with AutoCloseable {

  implicit val system = ActorSystem("reactor-scrap", coreConfig)

  val config = coreConfig.as[ConfigScrap]("scrap")

  val queueingActor = system
    .actorOf(
      Props(new QueueingActor(linksQueue, config))
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

  override def close() = {
    Try(linksQueue.close())
    Try(pageCache.close())
    Try(exporter.close())
  }
}

case class ConfigScrap(
  maxConcurrentRequests: Int,
  maxConcurrentRequestsPerDomain: Int,
  pullingInterval: FiniteDuration,
  processingTimeout: FiniteDuration,
  maxCrawlingDepth: Int,
  maxCrawlingRedirects: Int,
  downloadTimeout: FiniteDuration,
  maxPageSize: Long,
  scrapingThreads: Int
)

trait ActorScrap extends Actor with ActorLogging {
  def queueingActor = context.actorSelection("/user/queueing")
  def downloadingActor = context.actorSelection("/user/downloading")
  def scrapingActor = context.actorSelection("/user/scraping")
}
