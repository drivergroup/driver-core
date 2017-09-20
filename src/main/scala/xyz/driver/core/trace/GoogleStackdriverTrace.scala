package xyz.driver.core.trace

import java.io.FileInputStream
import java.util
import java.util.UUID

import akka.actor.ActorSystem
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.model.headers.RawHeader
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.trace.core._
import com.google.cloud.trace.grpc.v1.GrpcTraceConsumer
import com.google.cloud.trace.sink.TraceSink
import com.google.cloud.trace.v1.TraceSinkV1
import com.google.cloud.trace.v1.consumer.TraceConsumer
import com.google.cloud.trace.v1.producer.TraceProducer
import com.google.cloud.trace.{SpanContextHandler, SpanContextHandlerTracer, Tracer}
import com.typesafe.scalalogging.Logger

import scala.compat.java8.OptionConverters._ // needed for the `.asScala` implicit on the return type of .getHeader
import scala.collection.mutable

@SuppressWarnings(Array("org.wartremover.warts.MutableDataStructures"))
final class GoogleStackdriverTrace(projectId: String, clientSecretsFile: String, appEnvironment: String, log: Logger)(
        implicit system: ActorSystem)
    extends ServiceTracer {
  override type TraceId = UUID
  import GoogleStackdriverTrace._
  // initialize our various tracking storage systems
  private val contextMap: mutable.Map[TraceId, (Tracer, TraceContext)] =
    mutable.Map.empty[TraceId, (Tracer, TraceContext)]
  val clientSecretsInputStreamOpt: Option[FileInputStream] = if (fileExists(clientSecretsFile)) {
    Some(new FileInputStream(clientSecretsFile))
  } else {
    None
  }
  private val traceProducer: TraceProducer = new TraceProducer()
  // if the google credentials are invalid, just log the traces
  private val traceConsumer: TraceConsumer = clientSecretsInputStreamOpt.fold[TraceConsumer] {
    log.debug(s"Google credentials not found in path: $clientSecretsFile")
    new LoggingTraceConsumer(log)
  } { clientSecretsInputStream =>
    GrpcTraceConsumer
      .create(
        "cloudtrace.googleapis.com",
        GoogleCredentials
          .fromStream(clientSecretsInputStream)
          .createScoped(util.Arrays.asList("https://www.googleapis.com/auth/trace.append"))
      )
  }

  private val traceSink: TraceSink = new TraceSinkV1(projectId, traceProducer, traceConsumer)

  private val spanContextFactory: SpanContextFactory = new SpanContextFactory(
    new ConstantTraceOptionsFactory(true, true))
  private val timestampFactory: TimestampFactory = new JavaTimestampFactory()
  override val headerKey                         = HeaderKey
  override def startSpan(appName: String, httpRequest: HttpRequest): (TraceId, RawHeader) = {
    val traceId                                                        = UUID.randomUUID()
    val parentHeaderOption: Option[akka.http.javadsl.model.HttpHeader] = httpRequest.getHeader(HeaderKey).asScala
    val (spanContext: SpanContext, spanKind: SpanKind) = parentHeaderOption.fold {
      (spanContextFactory.initialContext(), SpanKind.RPC_CLIENT)
    } { parentHeader =>
      (spanContextFactory.fromHeader(parentHeader.value()), SpanKind.RPC_SERVER)
    }

    val contextHandler: SpanContextHandler = new SimpleSpanContextHandler(spanContext)
    val httpMethod                         = httpRequest.method.value
    val httpHost                           = httpRequest.uri.authority.host.address()
    val httpRelative                       = httpRequest.uri.toRelative.toString()
    val tracer: Tracer                     = new SpanContextHandlerTracer(traceSink, contextHandler, spanContextFactory, timestampFactory)
    // Create a span using the given timestamps.
    // https://cloud.google.com/trace/docs/reference/v1/rest/v1/projects.traces#TraceSpan
    val spanOptions: StartSpanOptions = (new StartSpanOptions()).setSpanKind(spanKind)

    val spanLabelBuilder = Labels
      .builder()
      .add("/http/method", httpMethod)
      .add("/http/url", httpRelative)
      .add("/http/host", httpHost)
      .add("/component", appName)
      .add("/environment", appEnvironment)

    parentHeaderOption.foreach { parentHeader =>
      spanLabelBuilder.add("/span/parent", parentHeader.value())
    }

    // The cloudTrace analysis reporting UI makes it easy to query by name prefix.
    // this spanName gives us the ability to grab things that are specific to a particular UDE/env, as well as all
    // endpoints in that service, as well as a particular endpoint in a particular environment/service.
    val spanName: String = s"($appEnvironment->$appName)$httpRelative"

    val context: TraceContext = tracer.startSpan(spanName, spanOptions)
    tracer.annotateSpan(context, spanLabelBuilder.build())

    synchronized {
      contextMap.put(traceId, (tracer, context))
    }

    (traceId, RawHeader(HeaderKey, SpanContextFactory.toHeader(context.getHandle.getCurrentSpanContext)))
  }

  override def endSpan(traceid: TraceId): Unit =
    contextMap.get(traceid) match {
      case Some((tracer, context)) =>
        tracer.endSpan(context)

        synchronized {
          contextMap.remove(traceid)
        }

      case None => log.error("ERROR you are asking to stop a span that was not found in tracing.")
    }
}

object GoogleStackdriverTrace {
  import java.nio.file.{Paths, Files}
  protected def fileExists(path: String): Boolean = Files.exists(Paths.get(path))
  val HeaderKey: String = {
    SpanContextFactory.headerKey()
  }
}
