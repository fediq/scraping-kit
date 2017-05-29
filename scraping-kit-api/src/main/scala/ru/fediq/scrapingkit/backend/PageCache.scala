package ru.fediq.scrapingkit.backend

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, StandardOpenOption}

import akka.http.scaladsl.model.{ContentType, HttpEntity, StatusCode, Uri}
import akka.util.ByteString
import org.apache.commons.codec.binary.Base64
import org.apache.commons.codec.digest.DigestUtils
import org.joda.time.DateTime
import ru.fediq.scrapingkit.model.PageRef
import ru.fediq.scrapingkit.util.Utilities
import spray.json._

import scala.concurrent.{ExecutionContext, ExecutionContextExecutor, Future}
import scala.util.Try

sealed trait CachedEntity

case class CachedPage(
  status: StatusCode,
  time: DateTime,
  body: HttpEntity.Strict
) extends CachedEntity

case class CachedRedirect(
  location: Uri,
  time: DateTime
) extends CachedEntity

case class CachedFailure(
  failure: String,
  time: DateTime
) extends CachedEntity

trait PageCache extends AutoCloseable {
  implicit def dispatcher: ExecutionContextExecutor = ExecutionContext.global

  def storeFetch(ref: PageRef, time: DateTime, status: StatusCode, body: HttpEntity.Strict): Future[Any] = {
    val lastUri = ref.lastUri
    val redirectsFutures = ref.redirectSteps.map { uri =>
      store(uri, CachedRedirect(lastUri, time))
    }
    val bodyFuture = store(lastUri, CachedPage(status, time, body))
    Future.sequence {
      val futures: Seq[Future[Any]] = redirectsFutures :+ bodyFuture
      futures
    }
  }

  def storeFailure(ref: PageRef, failure: String): Future[Any] = {
    val time: DateTime = DateTime.now()
    val lastUri = ref.lastUri
    val redirectsFutures = ref.redirectSteps.map { uri =>
      store(uri, CachedRedirect(lastUri, time))
    }
    val failureFuture = store(lastUri, CachedFailure(failure, time))
    Future.sequence {
      val futures: Seq[Future[Any]] = redirectsFutures :+ failureFuture
      futures
    }
  }

  protected def store(uri: Uri, entry: CachedEntity): Future[Any]

  def load(uri: Uri): Future[Option[CachedEntity]]

  override def close() = {
    // Do nothing
  }
}

class NoOpPageCache extends PageCache {
  override def store(uri: Uri, entity: CachedEntity) = {
    Future.successful()
  }

  override def load(uri: Uri) = {
    Future.successful(None)
  }
}

class FileSystemPageCache(rootPath: String, parallelism: Int = 1) extends PageCache with DefaultJsonProtocol {

  import FileSystemPageCache._

  implicit val dumpedCachedEntityFormat = jsonFormat7(DumpedCachedEntry)

  Files.createDirectories(new File(rootPath).toPath)

  override implicit val dispatcher = Utilities.poolDaemonDispatcher(parallelism, "page-cache")

  override protected def store(uri: Uri, entity: CachedEntity): Future[_] = Future {
    val url = uri.toString()
    val blob = serialize(url, entity)
    val path = preparePathToStore(url)
    Try(Files.write(path, blob, StandardOpenOption.CREATE_NEW))
  }

  override def load(uri: Uri): Future[Option[CachedEntity]] = Future {
    tryToLoad(uri.toString()) match {
      case None => None
      case Some(blob) => Try(deserialize(blob)).toOption
    }
  }

  private def preparePathToStore(url: String): Path = {
    val hash = DigestUtils.md5Hex(url)
    val f1 = hash.substring(0, 2)
    val f2 = hash.substring(2, 4)
    Files.createDirectories(new File(s"$rootPath/$f1/$f2/").toPath)
    new File(s"$rootPath/$f1/$f2/$hash").toPath
  }

  private def tryToLoad(url: String): Option[Array[Byte]] = {
    val hash = DigestUtils.md5Hex(url)
    val f1 = hash.substring(0, 2)
    val f2 = hash.substring(2, 4)
    val path = new File(s"$rootPath/$f1/$f2/$hash").toPath
    if (Files.exists(path)) {
      Some(Files.readAllBytes(path))
    } else {
      None
    }
  }

  private def serialize(url: String, entity: CachedEntity): Array[Byte] = {
    val dumped = entity match {
      case CachedRedirect(location, time) => DumpedCachedEntry(
        url = url,
        time = time.getMillis,
        redirect = Some(location.toString())
      )
      case CachedPage(status, time, body) => DumpedCachedEntry(
        url = url,
        time = time.getMillis,
        status = Some(status.intValue()),
        contentType = Some(body.contentType.value),
        body = Some(Base64.encodeBase64String(body.data.toArray))
      )
      case CachedFailure(failure, time) => DumpedCachedEntry(
        url = url,
        time = time.getMillis,
        failure = Some(failure)
      )
    }
    dumped.toJson.compactPrint.getBytes(StandardCharsets.UTF_8)
  }

  private def deserialize(blob: Array[Byte]): CachedEntity = {
    val dumped = new String(blob, StandardCharsets.UTF_8)
      .parseJson
      .convertTo[DumpedCachedEntry]

    dumped match {
      case DumpedCachedEntry(_, time, Some(status), Some(contentType), Some(body), None, None) =>
        CachedPage(
          status,
          new DateTime(time),
          HttpEntity.Strict(
            ContentType.parse(contentType).right.get,
            ByteString(Base64.decodeBase64(body))
          ))

      case DumpedCachedEntry(_, time, None, None, None, Some(redirect), None) =>
        CachedRedirect(Uri(redirect), new DateTime(time))

      case DumpedCachedEntry(_, time, None, None, None, None, Some(failure)) =>
        CachedFailure(failure, new DateTime(time))

      case entry => throw new IllegalArgumentException(s"Unexpected cached entry: $entry")
    }
  }
}

object FileSystemPageCache {

  case class DumpedCachedEntry(
    url: String,
    time: Long,
    status: Option[Int] = None,
    contentType: Option[String] = None,
    body: Option[String] = None,
    redirect: Option[String] = None,
    failure: Option[String] = None
  )


}
