package ru.fediq.scrapingkit

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.Uri
import com.codahale.metrics.Slf4jReporter
import com.codahale.metrics.Slf4jReporter.LoggingLevel
import com.typesafe.config.ConfigFactory
import org.scalatest.FlatSpec
import org.slf4j.LoggerFactory
import ru.fediq.scrapingkit.backend.{InMemoryFifoLinksQueue, InMemoryLinksHistory, NoOpFeedExporter, NoOpPageCache}
import ru.fediq.scrapingkit.model.PageRef
import ru.fediq.scrapingkit.scraper.HtmlCrawlingScraper
import ru.fediq.scrapingkit.util.Metrics

class ScrapingKitReactorTest extends FlatSpec {

  "Reactor" should "crawl something" in {
    val scraperName = "crawl"
    val scrapers = Map(scraperName -> new HtmlCrawlingScraper(scraperName))

    val config = ConfigFactory.load()
    val linksQueue = new InMemoryFifoLinksQueue()
    val linksHistory = new InMemoryLinksHistory()
    val pageCache = new NoOpPageCache()
    val exporter = new NoOpFeedExporter()

    val reactor = new ScrapingKitReactor(config, linksQueue, linksHistory, pageCache, exporter, scrapers)
    linksQueue.enqueue(PageRef(Uri("http://quotes.toscrape.com/"), scraperName))

    Slf4jReporter
      .forRegistry(Metrics.metricRegistry)
      .withLoggingLevel(LoggingLevel.INFO)
      .outputTo(LoggerFactory.getLogger("METRICS"))
      .convertDurationsTo(TimeUnit.MILLISECONDS)
      .convertRatesTo(TimeUnit.SECONDS)
      .build()
      .start(10, TimeUnit.SECONDS)

    Thread.sleep(10000)

    reactor.close()
  }
}