package ru.fediq.scrapingkit.tools

import akka.http.scaladsl.model.Uri
import ru.fediq.scrapingkit.backend.LinksHistory

import scala.concurrent.{ExecutionContext, Future}

class LinksHistoryFiller(
  linksHistory: LinksHistory
) {
  def add(uri: Uri)(implicit ec: ExecutionContext): Future[Any] = {
    linksHistory.isKnown(uri).flatMap {
      case true => Future.successful()
      case false => linksHistory.addKnown(uri)
    }
  }

  def fill(uris: Traversable[Uri])(implicit ec: ExecutionContext): Future[Any] = {
    val futures = uris.map(add)
    Future.sequence(futures)
  }
}
