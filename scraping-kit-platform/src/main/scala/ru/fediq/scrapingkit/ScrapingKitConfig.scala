package ru.fediq.scrapingkit

import scala.concurrent.duration.FiniteDuration

case class ScrapingKitConfig(
  maxConcurrentRequests: Int,
  maxConcurrentRequestsPerDomain: Int,
  pullingTimeout: FiniteDuration,
  pullingInterval: FiniteDuration,
  maxPullsInParallel: Int,
  logErrorsEach: Int,
  processingTimeout: FiniteDuration,
  maxCrawlingDepth: Double,
  maxCrawlingRedirects: Int,
  downloadTimeout: FiniteDuration,
  maxPageSize: Long,
  scrapingThreads: Int
)
