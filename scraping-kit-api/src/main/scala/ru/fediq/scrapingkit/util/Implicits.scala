package ru.fediq.scrapingkit.util

import spray.json.JsValue

import scala.concurrent.{ExecutionContext, Future}

object Implicits {
  implicit class StringToAnyMapWrapper(val map: Map[String, Any]) extends AnyVal {
    def mapToJson: Map[String, JsValue] = Utilities.mapToJson(map)
  }

  implicit class SequenceWrapper[A](val seq: Seq[A]) extends AnyVal {
    def foldFutures[B](z: B)(f: (B, A) => Future[B])(implicit ec: ExecutionContext): Future[B] = {
      var future: Future[B] = Future.successful(z)
      for (a: A <- seq) {
        future = future.flatMap(b => f(b, a))
      }
      future
    }

    def chainFutures(f: A => Future[_])(implicit ec: ExecutionContext): Future[_] = {
      foldFutures(AnyRef.asInstanceOf[Any])((_, a) => f(a))
    }
  }
}
