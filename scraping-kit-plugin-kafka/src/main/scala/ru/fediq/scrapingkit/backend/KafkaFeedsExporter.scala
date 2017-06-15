package ru.fediq.scrapingkit.backend

import cakesolutions.kafka.KafkaProducer
import org.apache.kafka.clients.producer.{ProducerRecord, RecordMetadata}
import org.apache.kafka.common.serialization.StringSerializer
import ru.fediq.scrapingkit.scraper.ScrapedEntity

import scala.concurrent.Future

class KafkaFeedsExporter(
  val bootstrapServer: String,
  val topic: String
) extends FeedExporter {
  val producer = KafkaProducer(KafkaProducer.Conf(new StringSerializer(), new StringSerializer, bootstrapServer))

  override def store[T <: ScrapedEntity](entity: T): Future[RecordMetadata] = {
    producer.send(new ProducerRecord(topic, entity.dump))
  }

  override def close() = producer.close()
}
