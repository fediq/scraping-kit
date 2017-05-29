package ru.fediq.scrapingkit

import scala.concurrent.duration.FiniteDuration

case class ScrapingKitConfig(
  maxConcurrentRequests: Int,
  maxConcurrentRequestsPerDomain: Int,
  pullingInterval: FiniteDuration,
  logErrorsEach: Int,
  processingTimeout: FiniteDuration,
  maxCrawlingDepth: Int,
  maxCrawlingRedirects: Int,
  downloadTimeout: FiniteDuration,
  maxPageSize: Long,
  scrapingThreads: Int
)
