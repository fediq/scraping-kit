package ru.fediq.scrapingkit.platform

import akka.actor.{Actor, ActorLogging}

trait ScrapingKitActor extends Actor with ActorLogging {
  def queueingActor = context.actorSelection("/user/queueing")

  def downloadingActor = context.actorSelection("/user/downloading")

  def scrapingActor = context.actorSelection("/user/scraping")
}
