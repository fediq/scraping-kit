package ru.fediq.scrapingkit

import scala.concurrent.duration.FiniteDuration

case class ScrapingKitConfig(
  maxConcurrentRequests: Int,
  maxConcurrentRequestsPerDomain: Int,
  pullingTimeout: FiniteDuration,
  pullingInterval: FiniteDuration,
  logErrorsEach: Int,
  processingTimeout: FiniteDuration,
  maxCrawlingDepth: Int,
  maxCrawlingRedirects: Int,
  downloadTimeout: FiniteDuration,
  maxPageSize: Long,
  scrapingThreads: Int
)
