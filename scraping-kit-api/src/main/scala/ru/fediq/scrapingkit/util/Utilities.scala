package ru.fediq.scrapingkit.util

import java.util.concurrent.Executors

import akka.dispatch.MonitorableThreadFactory
import org.slf4j.LoggerFactory
import spray.json.{JsArray, JsNumber, JsObject, JsString, JsValue}

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}
import scala.util.{Failure, Success, Try}

object Utilities {
  private val logger = LoggerFactory.getLogger("UNCAUGHT")

  def singleDaemonDispatcher(name: String): ExecutionContextExecutor = {
    ExecutionContext.fromExecutor(
      Executors.newSingleThreadExecutor(
        MonitorableThreadFactory(
          name = name,
          daemonic = true,
          contextClassLoader = None,
          exceptionHandler = loggingExceptionHandler
        )))
  }

  def singleActiveDispatcher(name: String): ExecutionContextExecutor = {
    ExecutionContext.fromExecutor(
      Executors.newSingleThreadExecutor(
        MonitorableThreadFactory(
          name = name,
          daemonic = false,
          contextClassLoader = None,
          exceptionHandler = loggingExceptionHandler
        )))
  }

  def poolDaemonDispatcher(size: Int, name: String): ExecutionContextExecutor = {
    ExecutionContext.fromExecutor(
      Executors.newFixedThreadPool(
        size,
        MonitorableThreadFactory(
          name = name,
          daemonic = true,
          contextClassLoader = None,
          exceptionHandler = loggingExceptionHandler
        )
      )
    )
  }

  def poolActiveDispatcher(size: Int, name: String): ExecutionContextExecutor = {
    ExecutionContext.fromExecutor(
      Executors.newFixedThreadPool(
        size,
        MonitorableThreadFactory(
          name = name,
          daemonic = false,
          contextClassLoader = None,
          exceptionHandler = loggingExceptionHandler
        )
      )
    )
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

  def mapToJson(map: Map[String, Any]): JsObject = {
    JsObject(map.mapValues(anyToJson))
  }

  def seqToJson(s: Seq[Any]): JsArray = {
    JsArray(s.map(anyToJson).toVector)
  }

  def anyToJson(a: Any): JsValue = a match {
    case s: String => JsString(s)
    case k: Int => JsNumber(k)
    case k: java.lang.Integer => JsNumber(k)
    case l: Long => JsNumber(l)
    case l: java.lang.Long => JsNumber(l)
    case f: Float => JsNumber(f.toDouble)
    case f: java.lang.Float => JsNumber(f.toDouble)
    case d: Double => JsNumber(d)
    case d: java.lang.Double => JsNumber(d)
    case s: Seq[Any] => seqToJson(s)
    case m: Map[String, Any] => mapToJson(m)
  }
}
