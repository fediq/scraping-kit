package ru.fediq.scrap.scraper

import org.scalatest.{FlatSpec, Matchers}
import spray.json.DefaultJsonProtocol

case class Foo(a: String) extends JsonScrapedEntity[Foo](Protocol.foo)

case class Boo(b: Int) extends PrintableScrapedEntity

class JsonScrapedEntityTest extends FlatSpec with Matchers {

  "JsonScrapedEntity" should "dump well" in {
    Foo("poo").dump shouldEqual """{"a":"poo"}"""
  }

  "PrintableScrapedEntitiy" should "dump well" in {
    Boo(3).dump shouldEqual """Boo(3)"""
  }

}

object Protocol extends DefaultJsonProtocol {
  val foo = DefaultJsonProtocol.jsonFormat1(Foo)
}
