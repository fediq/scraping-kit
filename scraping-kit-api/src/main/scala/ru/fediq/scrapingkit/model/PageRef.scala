package ru.fediq.scrapingkit.model

import akka.http.scaladsl.model.{HttpMethod, HttpMethods, Uri}

case class PageRef(
  uri: Uri,
  method: HttpMethod,
  scraperName: String,
  depth: Int = 1,
  context: Map[String, String] = Map.empty,
  redirectsChain: List[Uri] = Nil
) {
  def lastUri = redirectsChain.headOption.getOrElse(uri)

  def redirectSteps = if (redirectsChain.isEmpty) Nil else uri :: redirectsChain.tail

  def chain(nextLocation: Uri) = copy(
    method = HttpMethods.GET,
    redirectsChain = nextLocation :: redirectsChain
  )
}
