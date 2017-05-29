name := "scraping-kit"

lazy val commonSettings = Seq(
  organization := "ru.fediq.scrapingkit",
  version := "0.4.0",
  scalaVersion := "2.11.8",
  resolvers ++= Seq(
    Resolver.bintrayRepo("hajile", "maven"), // Akka DNS
    Resolver.bintrayRepo("fediq", "maven") // Fediq's repository
  ),
  licenses += ("MIT", url("http://opensource.org/licenses/MIT"))
)

lazy val noPublishSettings = Seq(
  publish := {},
  publishLocal := {},
  publishM2 := {},
  bintrayUnpublish := {},
  bintrayRelease := {}
)

lazy val `scraping-kit-api` = project
  .in(file("scraping-kit-api"))
  .settings(commonSettings)

lazy val `scraping-kit-platform` = project
  .in(file("scraping-kit-platform"))
  .settings(commonSettings)
  .dependsOn(`scraping-kit-api`)

lazy val `scraping-kit-plugin-bloom` = project
  .in(file("scraping-kit-plugin-bloom"))
  .settings(commonSettings)
  .dependsOn(`scraping-kit-api`)

lazy val `scraping-kit-plugin-rmq` = project
  .in(file("scraping-kit-plugin-rmq"))
  .settings(commonSettings)
  .dependsOn(`scraping-kit-api`)

lazy val `scraping-kit-test` = project
  .in(file("scraping-kit-test"))
  .settings(commonSettings ++ noPublishSettings)
  .dependsOn(
    `scraping-kit-platform`,
    `scraping-kit-plugin-bloom`,
    `scraping-kit-plugin-rmq`
  )

lazy val `scraping-kit` = project
  .in(file("."))
  .settings(commonSettings ++ noPublishSettings)
  .aggregate(
    `scraping-kit-api`,
    `scraping-kit-platform`,
    `scraping-kit-plugin-bloom`,
    `scraping-kit-plugin-rmq`,
    `scraping-kit-test`
  )
