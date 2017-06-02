package ru.fediq.scrapingkit.util

import org.scalatest.{FlatSpec, Matchers}

class UtilitiesTest extends FlatSpec with Matchers {
  behavior of "json mapping"

  it should "map objects" in {
    val obj: Map[String, Any] = Map(
      "a" -> 1.5f,
      "b" -> 1.4,
      "c" -> "foog",
      "d" -> List(1, 2L, "poo"),
      "e" -> Seq(Vector(6, "nyam"), "hi", Map("x" -> "X I said", "y" -> "9", "z" -> 0.3))
    )
    Utilities.anyToJson(obj).prettyPrint shouldEqual """{
                                                       |  "e": [[6, "nyam"], "hi", {
                                                       |    "x": "X I said",
                                                       |    "y": "9",
                                                       |    "z": 0.3
                                                       |  }],
                                                       |  "a": 1.5,
                                                       |  "b": 1.4,
                                                       |  "c": "foog",
                                                       |  "d": [1, 2, "poo"]
                                                       |}""".stripMargin

  }
}
