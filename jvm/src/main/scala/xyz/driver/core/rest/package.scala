package xyz.driver.core.rest

import java.net.InetAddress

import akka.http.scaladsl.marshalling.{ToEntityMarshaller, ToResponseMarshallable}
import akka.http.scaladsl.model.headers._
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import akka.util.ByteString

import scala.concurrent.Future
import scala.util.Try
import scalaz.{Functor, OptionT}
import scalaz.Scalaz.{intInstance, stringInstance}
import scalaz.syntax.equal._

trait HttpClient {
  def makeRequest(request: HttpRequest): Future[HttpResponse]
}

trait ServiceTransport {

  def sendRequestGetResponse(context: ServiceRequestContext)(requestStub: HttpRequest): Future[HttpResponse]

  def sendRequest(context: ServiceRequestContext)(requestStub: HttpRequest)(
      implicit mat: Materializer): Future[Unmarshal[ResponseEntity]]
}

object `package` {
  implicit class OptionTRestAdditions[T](optionT: OptionT[Future, T]) {
    def responseOrNotFound(successCode: StatusCodes.Success = StatusCodes.OK)(
        implicit F: Functor[Future],
        em: ToEntityMarshaller[T]): Future[ToResponseMarshallable] = {
      optionT.fold[ToResponseMarshallable](successCode -> _, StatusCodes.NotFound -> None)
    }
  }

  val AllowedHeaders: Seq[String] =
    Seq(
      "Origin",
      "X-Requested-With",
      "Content-Type",
      "Content-Length",
      "Accept",
      "X-Trace",
      "Access-Control-Allow-Methods",
      "Access-Control-Allow-Origin",
      "Access-Control-Allow-Headers",
      "Server",
      "Date",
      ContextHeaders.ClientFingerprintHeader,
      ContextHeaders.TrackingIdHeader,
      ContextHeaders.TraceHeaderName,
      ContextHeaders.SpanHeaderName,
      ContextHeaders.StacktraceHeader,
      ContextHeaders.AuthenticationTokenHeader,
      ContextHeaders.OriginatingIpHeader,
      ContextHeaders.ResourceCount,
      ContextHeaders.PageCount,
      "X-Frame-Options",
      "X-Content-Type-Options",
      "Strict-Transport-Security",
      AuthProvider.SetAuthenticationTokenHeader,
      AuthProvider.SetPermissionsTokenHeader
    )

  def allowOrigin(originHeader: Option[Origin]): `Access-Control-Allow-Origin` =
    `Access-Control-Allow-Origin`(
      originHeader.fold[HttpOriginRange](HttpOriginRange.*)(h => HttpOriginRange(h.origins: _*)))

  def serviceContext: Directive1[ServiceRequestContext] = {
    extractClientIP flatMap { remoteAddress =>
      extract(ctx => extractServiceContext(ctx.request, remoteAddress))
    }
  }

  def respondWithCorsAllowedHeaders: Directive0 = {
    respondWithHeaders(
      List[HttpHeader](
        `Access-Control-Allow-Headers`(AllowedHeaders: _*),
        `Access-Control-Expose-Headers`(AllowedHeaders: _*)
      ))
  }

  def respondWithCorsAllowedOriginHeaders(origin: Origin): Directive0 = {
    respondWithHeader {
      `Access-Control-Allow-Origin`(HttpOriginRange(origin.origins: _*))
    }
  }

  def respondWithCorsAllowedMethodHeaders(methods: Set[HttpMethod]): Directive0 = {
    respondWithHeaders(
      List[HttpHeader](
        Allow(methods.to[collection.immutable.Seq]),
        `Access-Control-Allow-Methods`(methods.to[collection.immutable.Seq])
      ))
  }

  def extractServiceContext(request: HttpRequest, remoteAddress: RemoteAddress): ServiceRequestContext =
    new ServiceRequestContext(
      extractTrackingId(request),
      extractOriginatingIP(request, remoteAddress),
      extractContextHeaders(request))

  def extractTrackingId(request: HttpRequest): String = {
    request.headers
      .find(_.name === ContextHeaders.TrackingIdHeader)
      .fold(java.util.UUID.randomUUID.toString)(_.value())
  }

  def extractFingerprintHash(request: HttpRequest): Option[String] = {
    request.headers
      .find(_.name === ContextHeaders.ClientFingerprintHeader)
      .map(_.value())
  }

  def extractOriginatingIP(request: HttpRequest, remoteAddress: RemoteAddress): Option[InetAddress] = {
    request.headers
      .find(_.name === ContextHeaders.OriginatingIpHeader)
      .flatMap(ipName => Try(InetAddress.getByName(ipName.value)).toOption)
      .orElse(remoteAddress.toOption)
  }

  def extractStacktrace(request: HttpRequest): Array[String] =
    request.headers.find(_.name == ContextHeaders.StacktraceHeader).fold("")(_.value()).split("->")

  def extractContextHeaders(request: HttpRequest): Map[String, String] = {
    request.headers.filter { h =>
      h.name === ContextHeaders.AuthenticationTokenHeader || h.name === ContextHeaders.TrackingIdHeader ||
      h.name === ContextHeaders.PermissionsTokenHeader || h.name === ContextHeaders.StacktraceHeader ||
      h.name === ContextHeaders.TraceHeaderName || h.name === ContextHeaders.SpanHeaderName ||
      h.name === ContextHeaders.OriginatingIpHeader || h.name === ContextHeaders.ClientFingerprintHeader
    } map { header =>
      if (header.name === ContextHeaders.AuthenticationTokenHeader) {
        header.name -> header.value.stripPrefix(ContextHeaders.AuthenticationHeaderPrefix).trim
      } else {
        header.name -> header.value
      }
    } toMap
  }

  private[rest] def escapeScriptTags(byteString: ByteString): ByteString = {
    @annotation.tailrec
    def dirtyIndices(from: Int, descIndices: List[Int]): List[Int] = {
      val index = byteString.indexOf('/', from)
      if (index === -1) descIndices.reverse
      else {
        val (init, tail) = byteString.splitAt(index)
        if ((init endsWith "<") && (tail startsWith "/sc")) {
          dirtyIndices(index + 1, index :: descIndices)
        } else {
          dirtyIndices(index + 1, descIndices)
        }
      }
    }

    val indices = dirtyIndices(0, Nil)

    indices.headOption.fold(byteString) { head =>
      val builder = ByteString.newBuilder
      builder ++= byteString.take(head)

      (indices :+ byteString.length).sliding(2).foreach {
        case Seq(start, end) =>
          builder += ' '
          builder ++= byteString.slice(start, end)
        case Seq(_) => // Should not match; sliding on at least 2 elements
          assert(indices.nonEmpty, s"Indices should have been nonEmpty: $indices")
      }
      builder.result
    }
  }

  val sanitizeRequestEntity: Directive0 = {
    mapRequest(request => request.mapEntity(entity => entity.transformDataBytes(Flow.fromFunction(escapeScriptTags))))
  }

  val paginated: Directive1[Pagination] =
    parameters(("pageSize".as[Int] ? 100, "pageNumber".as[Int] ? 1)).as(Pagination)

  private def extractPagination(pageSizeOpt: Option[Int], pageNumberOpt: Option[Int]): Option[Pagination] =
    (pageSizeOpt, pageNumberOpt) match {
      case (Some(size), Some(number)) => Option(Pagination(size, number))
      case (None, None)               => Option.empty[Pagination]
      case (_, _)                     => throw new IllegalArgumentException("Pagination's parameters are incorrect")
    }

  val optionalPagination: Directive1[Option[Pagination]] =
    parameters(("pageSize".as[Int].?, "pageNumber".as[Int].?)).as(extractPagination)

  def paginationQuery(pagination: Pagination) =
    Seq("pageNumber" -> pagination.pageNumber.toString, "pageSize" -> pagination.pageSize.toString)

  private def extractSorting(sortingString: Option[String]): Sorting = {
    val sortingFields = sortingString.fold(Seq.empty[SortingField])(
      _.split(",")
        .filter(_.length > 0)
        .map { sortingParam =>
          if (sortingParam.startsWith("-")) {
            SortingField(sortingParam.substring(1), SortingOrder.Desc)
          } else {
            val fieldName = if (sortingParam.startsWith("+")) sortingParam.substring(1) else sortingParam
            SortingField(fieldName, SortingOrder.Asc)
          }
        }
        .toSeq)

    Sorting(sortingFields)
  }

  val sorting: Directive1[Sorting] = parameter("sort".as[String].?).as(extractSorting)

  def sortingQuery(sorting: Sorting): Seq[(String, String)] = {
    val sortingString = sorting.sortingFields
      .map { sortingField =>
        sortingField.sortingOrder match {
          case SortingOrder.Asc  => sortingField.name
          case SortingOrder.Desc => s"-${sortingField.name}"
        }
      }
      .mkString(",")
    Seq("sort" -> sortingString)
  }
}