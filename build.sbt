name := "scraping-kit"

lazy val commonSettings = Seq(
  organization := "ru.fediq.scrapingkit",
  version := "0.2.0-SNAPSHOT",
  scalaVersion := "2.11.8",
  publishTo := Some(Resolver.mavenLocal),
  resolvers ++= Seq(
    Resolver.bintrayRepo("hajile", "maven"),
    Resolver.bintrayRepo("fediq", "maven"),
    "SpinGo OSS" at "http://spingo-oss.s3.amazonaws.com/repositories/releases"
  )
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

lazy val `scraping-kit` = project
  .in(file("."))
  .aggregate(
    `scraping-kit-api`,
    `scraping-kit-platform`,
    `scraping-kit-plugin-bloom`,
    `scraping-kit-plugin-rmq`,
    `scraping-kit-test`
  )
