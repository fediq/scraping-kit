package ru.fediq.scrap

import com.codahale.metrics.MetricRegistry
import nl.grons.metrics.scala.InstrumentedBuilder

trait Metrics extends InstrumentedBuilder {
  override val metricRegistry = Metrics.metricRegistry
}

object Metrics {
  val metricRegistry = new MetricRegistry()
}
