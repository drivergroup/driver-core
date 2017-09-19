package xyz.driver.core.trace

import java.io.FileInputStream
import java.util
import java.util.UUID

import akka.actor.ActorSystem
import akka.http.scaladsl.model.headers.RawHeader
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.trace.core.{ConstantTraceOptionsFactory, JavaTimestampFactory, SpanContextFactory, TimestampFactory, TraceContext, _}
import com.google.cloud.trace.grpc.v1.GrpcTraceConsumer
import com.google.cloud.trace.sink.TraceSink
import com.google.cloud.trace.v1.TraceSinkV1
import com.google.cloud.trace.v1.consumer.TraceConsumer
import com.google.cloud.trace.v1.producer.TraceProducer
import com.google.cloud.trace.{SpanContextHandler, SpanContextHandlerTracer, Tracer}

import scala.collection.mutable

@SuppressWarnings(
  Array("org.wartremover.warts.MutableDataStructures"))
final class GoogleStackdriverTrace(projectId: String,
                                   clientSecretsFile:String)(implicit system: ActorSystem) extends DriverTracer{

  // initialize our various tracking storage systems
  private val contextMap:mutable.Map[UUID, (Tracer, TraceContext)] = synchronized(
    mutable.Map.empty[UUID, (Tracer, TraceContext)])

  private val traceProducer: TraceProducer = new TraceProducer()
  private val traceConsumer: TraceConsumer = GrpcTraceConsumer
    .create("cloudtrace.googleapis.com",
      GoogleCredentials.fromStream(new FileInputStream(clientSecretsFile))
        .createScoped(util.Arrays.asList("https://www.googleapis.com/auth/trace.append")))

  private val traceSink: TraceSink = new TraceSinkV1(projectId, traceProducer, traceConsumer)

  private val spanContextFactory: SpanContextFactory = new SpanContextFactory(new ConstantTraceOptionsFactory(true, true))
  private val timestampFactory: TimestampFactory = new JavaTimestampFactory()

  override val HeaderKey: String = {
    SpanContextFactory.headerKey()
  }

  override def startSpan(appName:String,
                         httpMethod: String,
                         uri: String,
                         parentTraceHeaderStringOpt: Option[String]): (UUID, RawHeader) = {
    val uuid = UUID.randomUUID()
    val spanContext: SpanContext = parentTraceHeaderStringOpt
      .fold(spanContextFactory.initialContext())(
        parentTraceHeaderString => spanContextFactory.fromHeader(parentTraceHeaderString)
      )
    val contextHandler: SpanContextHandler = new SimpleSpanContextHandler(spanContext)

    val tracer: Tracer = new SpanContextHandlerTracer(traceSink, contextHandler, spanContextFactory, timestampFactory)

    // Create a span using the given timestamps.
    // https://cloud.google.com/trace/docs/reference/v1/rest/v1/projects.traces#TraceSpan
    val context: TraceContext = tracer.startSpan(s"$appName:$uri")
    tracer.annotateSpan(context, Labels.builder()
      .add("/http/method", httpMethod)
      .add("/http/url", uri)
      .add("/component", appName)
      .build())
    contextMap.put(uuid, (tracer, context))
    (uuid, RawHeader(HeaderKey, SpanContextFactory.toHeader(context.getHandle.getCurrentSpanContext)))
  }

  override def endSpan(uuid: UUID): Unit =
    contextMap.get(uuid) match {
      case Some((tracer, context)) =>
        tracer.endSpan(context)
        contextMap.remove(uuid)
      case None => () // do nothing here
  }
}
