package ru.fediq.scrapingkit.backend

import akka.actor.ActorSystem
import redis.api.pubsub.Message
import redis.{RedisClient, RedisPubSub}

trait RedisSupport {
  implicit def system: ActorSystem

  def redisHost: String

  def redisPort: Int

  def redisPassword: String

  def redisDatabase: Option[Int] = None

  def redisName: String

  val redis = RedisClient(redisHost, redisPort, Some(redisPassword), redisDatabase, redisName)

  protected def subscribe(channel: String, callback: Message => Unit) = RedisPubSub(
    host = redisHost,
    port = redisPort,
    channels = Seq(channel),
    patterns = Nil,
    onMessage = callback,
    authPassword = Some(redisPassword),
    name = buildSubscriptionName(channel)
  )

  private def buildSubscriptionName(channel: String) = s"$redisName:sub:$channel"
}
