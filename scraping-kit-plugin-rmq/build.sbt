name := "scraping-kit-plugin-rmq"

libraryDependencies ++= Seq(
//  "com.newmotion" %% "akka-rabbitmq" % "4.0.0",
  "com.github.sstone" %% "amqp-client" % "1.5",
  "org.scalatest" %% "scalatest" % "3.0.1" % "test"
)