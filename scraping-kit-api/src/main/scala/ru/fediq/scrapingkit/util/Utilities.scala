package ru.fediq.scrapingkit.util

import java.util.concurrent.Executors

import akka.dispatch.MonitorableThreadFactory
import org.slf4j.LoggerFactory
import spray.json.{JsNumber, JsObject, JsString, JsValue}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.util.{Failure, Success, Try}

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

  def tryAndCleanup[A, B](resource: A)(work: A => B)(cleanup: A => Unit): Try[B] = {
    try {
      Success(work(resource))
    } catch {
      case e: Exception => Failure(e)
    } finally {
      try {
        if (resource != null) {
          cleanup(resource)
        }
      } catch {
        case e: Exception => println(e) // should be logged
      }
    }
  }

  def tryAndClose[A <: AutoCloseable, B](resource: A)(work: A => B): Try[B] = {
    tryAndCleanup(resource)(work)(_.close())
  }

  def mapToJson(map: Map[String, Any]): Map[String, JsValue] = {
    map
      .mapValues {
        case s: String => JsString(s)
        case k: Int => JsNumber(k)
        case k: java.lang.Integer => JsNumber(k)
        case d: Double => JsNumber(d)
        case d: java.lang.Double => JsNumber(d)
        case l: Long => JsNumber(l)
        case l: java.lang.Long => JsNumber(l)
      }
      .mapValues(_.asInstanceOf[JsValue])
  }
}
