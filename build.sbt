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

lazy val apiProject = project
  .in(file("scraping-kit-api"))
  .settings(commonSettings)

lazy val platformProject = project
  .in(file("scraping-kit-platform"))
  .dependsOn(apiProject)
  .settings(commonSettings)

lazy val bloomPluginProject = project
  .in(file("scraping-kit-plugin-bloom"))
  .dependsOn(apiProject)
  .settings(commonSettings)

lazy val rmqPluginProject = project
  .in(file("scraping-kit-plugin-rmq"))
  .dependsOn(apiProject)
  .settings(commonSettings)

lazy val rootProject = project
  .in(file("."))
  .aggregate(
    apiProject,
    platformProject,
    bloomPluginProject,
    rmqPluginProject
  )
