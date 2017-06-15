name := "scraping-kit-plugin-redis"

libraryDependencies ++= Seq(
  "com.typesafe.scala-logging" %% "scala-logging" % "3.5.0",
  "com.github.etaty" %% "rediscala" % "1.8.0",

  "org.scalatest" %% "scalatest" % "3.0.1" % "test"
)