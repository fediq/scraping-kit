package ru.fediq.scrap

import java.util.concurrent.Executors

import akka.dispatch.MonitorableThreadFactory
import org.slf4j.LoggerFactory

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

object Utilities {
  private val logger = LoggerFactory.getLogger("UNCAUGHT")
  def singleDaemonThreadDispatcher(name: String): ExecutionContextExecutor = {
    ExecutionContext.fromExecutor(
      Executors.newSingleThreadExecutor(
        MonitorableThreadFactory(
          name = name,
          daemonic = true,
          contextClassLoader = None,
          exceptionHandler = loggingExceptionHandler
        )))
  }

  def singleActiveThreadDispatcher(name: String): ExecutionContextExecutor = {
    ExecutionContext.fromExecutor(
      Executors.newSingleThreadExecutor(
        MonitorableThreadFactory(
          name = name,
          daemonic = false,
          contextClassLoader = None,
          exceptionHandler = loggingExceptionHandler
        )))
  }

  def loggingExceptionHandler = new Thread.UncaughtExceptionHandler {
    override def uncaughtException(t: Thread, e: Throwable) = {
      logger.error(s"Unchaught exception in thread ${t.getName}", e)
    }
  }
}

