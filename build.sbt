name := "scala-scrap"

organization := "ru.fediq.scrap"

version := "0.1.3-SNAPSHOT"

scalaVersion := "2.12.2"

resolvers += Resolver.bintrayRepo("hajile", "maven")

publishTo := Some(Resolver.mavenLocal)

val akkaVersion = "2.4.18"
val akkaHttpVersion = "10.0.6"

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.2.1",
  "com.iheart" %% "ficus" % "1.4.0",
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "ru.smslv.akka" %% "akka-dns" % "2.4.2",
  "commons-codec" % "commons-codec" % "1.9",
  "org.jsoup" % "jsoup" % "1.8.3",
  "joda-time" % "joda-time" % "2.9.7",
  "com.github.nscala-time" %% "nscala-time" % "2.16.0",

  "io.dropwizard.metrics" % "metrics-core" % "3.1.2",
  "nl.grons" %% "metrics-scala" % "3.5.5",

  "org.scalatest" %% "scalatest" % "3.0.1" % "test"
)
