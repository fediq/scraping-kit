package ru.fediq.scrap

import java.util.concurrent.TimeUnit

import akka.http.scaladsl.model.Uri
import com.codahale.metrics.Slf4jReporter
import com.codahale.metrics.Slf4jReporter.LoggingLevel
import com.typesafe.config.ConfigFactory
import org.scalatest.FlatSpec
import org.slf4j.LoggerFactory
import ru.fediq.scrap.backend._
import ru.fediq.scrap.platform.PageRef
import ru.fediq.scrap.scraper.HtmlCrawlingScraper

class ReactorScrapTest extends FlatSpec {

  "Reactor" should "crawl something" in {
    val scraperName = "crawl"
    val scrapers = Map(scraperName -> new HtmlCrawlingScraper(scraperName))

    val config = ConfigFactory.load()
    val linksQueue = new InMemoryFifoLinksQueue()
    val pageCache = new NoOpPageCache()
    val exporter = new NoOpFeedExporter()

    val reactor = new ReactorScrap(config, linksQueue, pageCache, exporter, scrapers)
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
