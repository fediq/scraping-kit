package ru.fediq.scrapingkit.backend

import akka.http.scaladsl.model.Uri
import ru.fediq.scrapingkit.model.PageRef

object RmqConstants {
  val connectorActorName = "links-queue"
  val channelActorName = "rmqchannel"
  val channelActorPath = "/user/links-queue/rmqchannel"

  case class SerializedPageRef(
    uri: String,
    scraper: String,
    depth: Int,
    context: Map[String, String]
  ) {
    def deserialize(): PageRef = PageRef(Uri(uri), scraper, depth, context)
  }

}
