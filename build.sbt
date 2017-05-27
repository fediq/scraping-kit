name := "scraping-kit"

lazy val commonSettings = Seq(
  organization := "ru.fediq.scrapingkit",
  version := "0.2.0",
  scalaVersion := "2.11.8",
  resolvers ++= Seq(
    Resolver.bintrayRepo("hajile", "maven"), // Akka DNS
    Resolver.bintrayRepo("fediq", "maven") // Fediq's repository
  ),
  licenses += ("MIT", url("http://opensource.org/licenses/MIT"))
)

lazy val `scraping-kit-api` = project
  .in(file("scraping-kit-api"))
  .settings(commonSettings)

lazy val `scraping-kit-platform` = project
  .in(file("scraping-kit-platform"))
  .dependsOn(`scraping-kit-api`)
  .settings(commonSettings)

lazy val `scraping-kit-plugin-bloom` = project
  .in(file("scraping-kit-plugin-bloom"))
  .dependsOn(`scraping-kit-api`)
  .settings(commonSettings)

lazy val `scraping-kit-plugin-rmq` = project
  .in(file("scraping-kit-plugin-rmq"))
  .dependsOn(`scraping-kit-api`)
  .settings(commonSettings)

lazy val `scraping-kit-test` = project
  .in(file("scraping-kit-test"))
  .dependsOn(
    `scraping-kit-platform`,
    `scraping-kit-plugin-bloom`,
    `scraping-kit-plugin-rmq`
  )
  .settings(commonSettings)

// TODO unpublish root and tests when the following becomes fixed:
// https://github.com/sbt/sbt-bintray/issues/93

lazy val `scraping-kit` = project
  .in(file("."))
  .settings(commonSettings)
  .aggregate(
    `scraping-kit-api`,
    `scraping-kit-platform`,
    `scraping-kit-plugin-bloom`,
    `scraping-kit-plugin-rmq`,
    `scraping-kit-test`
  )
