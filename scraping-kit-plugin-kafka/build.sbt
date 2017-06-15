name := "scraping-kit-plugin-kafka"

libraryDependencies ++= Seq(
  "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
  "net.cakesolutions" %% "scala-kafka-client" % "0.10.2.2",
  "net.cakesolutions" %% "scala-kafka-client-akka" % "0.10.2.2",

  "net.cakesolutions" %% "scala-kafka-client-testkit" % "0.10.2.2" % "test",
  "org.scalatest" %% "scalatest" % "3.0.1" % "test"
)