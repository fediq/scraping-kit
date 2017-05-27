package ru.fediq.scrapingkit.platform

import akka.actor.{Actor, ActorLogging, Status}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

trait ScrapingKitActor extends Actor with ActorLogging {
  def queueingActor = context.actorSelection("/user/queueing")

  def downloadingActor = context.actorSelection("/user/downloading")

  def scrapingActor = context.actorSelection("/user/scraping")

  implicit class FutureWrapper[T](val future: Future[T]) {
    def pipeFailures(implicit ec: ExecutionContext): Future[T] = {
      future andThen {
        case Failure(f) ⇒ self ! Status.Failure(f)
      }
    }
  }
}
