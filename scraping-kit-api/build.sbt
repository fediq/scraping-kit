name := "scraping-kit-api"

val akkaVersion = "2.4.18"
val akkaHttpVersion = "10.0.6"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-http" % akkaHttpVersion,
  "com.typesafe.akka" %% "akka-http-spray-json" % akkaHttpVersion,
  "org.jsoup" % "jsoup" % "1.8.3",
  "commons-codec" % "commons-codec" % "1.9",
  "joda-time" % "joda-time" % "2.9.7",
  "io.dropwizard.metrics" % "metrics-core" % "3.1.2",
  "nl.grons" %% "metrics-scala" % "3.5.5",

  "org.scalatest" %% "scalatest" % "3.0.1" % "test"
)