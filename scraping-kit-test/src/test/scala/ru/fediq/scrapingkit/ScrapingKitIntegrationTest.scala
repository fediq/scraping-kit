package ru.fediq.scrapingkit

import java.nio.file.{Files, Path}
import java.util.concurrent.TimeUnit

import akka.actor.ActorSystem
import akka.http.scaladsl.model.Uri
import com.codahale.metrics.Slf4jReporter
import com.codahale.metrics.Slf4jReporter.LoggingLevel
import com.typesafe.config.ConfigFactory
import org.apache.commons.io.FileUtils
import org.scalatest.{BeforeAndAfter, FlatSpec}
import org.slf4j.LoggerFactory
import ru.fediq.scrapingkit.backend._
import ru.fediq.scrapingkit.model.PageRef
import ru.fediq.scrapingkit.scraper.HtmlCrawlingScraper
import ru.fediq.scrapingkit.util.Metrics

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.language.postfixOps

// TODO write proper docker test
class ScrapingKitIntegrationTest extends FlatSpec with BeforeAndAfter {

  var tempPath: Path = _

  before {
    tempPath = Files.createTempDirectory("scrapingkit")
  }

  after {
    FileUtils.deleteDirectory(tempPath.toFile)
  }

  "Scraping kit" should "crawl something" in {
    val scraperName = "crawl"
    val scrapers = Map(scraperName -> new HtmlCrawlingScraper(scraperName))

    val config = ConfigFactory.load()
    implicit val system = ActorSystem("scraping-kit-test", config)

    val linksQueue = new RmqFifoLinksQueue("amqp://localhost", "scrapingKit", 1 seconds, 10 seconds)
    val linksHistory = new BloomFilterLinksHistory(10000000, 0.01f, Some(tempPath + "/history.bin"))
    val pageCache = new FileSystemPageCache(tempPath + "/cache/", 4)
    val exporter = new JsonLinesFeedExporter(tempPath + "/output,jsonl")

    val reactor = new ScrapingKitReactor(linksQueue, linksHistory, pageCache, exporter, scrapers)
    val f = linksQueue.enqueue(PageRef(Uri("http://quotes.toscrape.com/"), scraperName))
    Await.result(f, 10 seconds)

    Slf4jReporter
      .forRegistry(Metrics.metricRegistry)
      .withLoggingLevel(LoggingLevel.INFO)
      .outputTo(LoggerFactory.getLogger("METRICS"))
      .convertDurationsTo(TimeUnit.MILLISECONDS)
      .convertRatesTo(TimeUnit.SECONDS)
      .build()
      .start(10, TimeUnit.SECONDS)

    Thread.sleep(60000)

    system.terminate()
  }
}
