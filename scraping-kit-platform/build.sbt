name := "scraping-kit-platform"

val akkaVersion = "2.4.18"
val akkaHttpVersion = "10.0.6"

libraryDependencies ++= Seq(
  "ch.qos.logback" % "logback-classic" % "1.2.1",
  "com.iheart" %% "ficus" % "1.4.0",
  "com.typesafe.akka" %% "akka-slf4j" % akkaVersion,
  "ru.smslv.akka" %% "akka-dns" % "2.4.2",
  "com.github.nscala-time" %% "nscala-time" % "2.16.0",

  "org.scalatest" %% "scalatest" % "3.0.1" % "test"
)