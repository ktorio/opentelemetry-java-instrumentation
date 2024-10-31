/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.instrumentation.ktor.v2_0.server

import io.ktor.events.EventDefinition
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.routing.Routing.Plugin.RoutingCallStarted
import io.ktor.util.*
import io.ktor.util.pipeline.*
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributesBuilder
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.Context
import io.opentelemetry.extension.kotlin.asContextElement
import io.opentelemetry.instrumentation.api.incubator.builder.internal.DefaultHttpServerInstrumenterBuilder
import io.opentelemetry.instrumentation.api.instrumenter.AttributesExtractor
import io.opentelemetry.instrumentation.api.instrumenter.SpanKindExtractor
import io.opentelemetry.instrumentation.api.instrumenter.SpanStatusBuilder
import io.opentelemetry.instrumentation.api.instrumenter.SpanStatusExtractor
import io.opentelemetry.instrumentation.api.internal.InstrumenterUtil
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRoute
import io.opentelemetry.instrumentation.api.semconv.http.HttpServerRouteSource
import io.opentelemetry.instrumentation.ktor.v2_0.InstrumentationProperties.INSTRUMENTATION_NAME
import io.opentelemetry.instrumentation.ktor.v2_0.internal.KtorBuilderUtil
import kotlinx.coroutines.withContext

class KtorServerTracingBuilder {
  companion object {
    init {
      KtorBuilderUtil.serverBuilderExtractor = { it.serverBuilder }
    }
  }

  internal lateinit var serverBuilder: DefaultHttpServerInstrumenterBuilder<ApplicationRequest, ApplicationResponse>

  internal var spanKindExtractor:
    (SpanKindExtractor<ApplicationRequest>) -> SpanKindExtractor<ApplicationRequest> = { a -> a }

  fun setOpenTelemetry(openTelemetry: OpenTelemetry) {
    this.serverBuilder =
      DefaultHttpServerInstrumenterBuilder.create(
        INSTRUMENTATION_NAME,
        openTelemetry,
        KtorHttpServerAttributesGetter.INSTANCE
      )
  }

  @Deprecated("Please use method `spanStatusExtractor`")
  fun setStatusExtractor(
    extractor: (SpanStatusExtractor<in ApplicationRequest, in ApplicationResponse>) -> SpanStatusExtractor<in ApplicationRequest, in ApplicationResponse>
  ) {
    spanStatusExtractor { prevStatusExtractor ->
      extractor(prevStatusExtractor).extract(spanStatusBuilder, request, response, error)
    }
  }

  fun spanStatusExtractor(extract: SpanStatusData.(SpanStatusExtractor<in ApplicationRequest, in ApplicationResponse>) -> Unit) {
    serverBuilder.setStatusExtractor { prevExtractor ->
      SpanStatusExtractor { spanStatusBuilder: SpanStatusBuilder,
                            request: ApplicationRequest,
                            response: ApplicationResponse?,
                            throwable: Throwable? ->
        extract(
          SpanStatusData(spanStatusBuilder, request, response, throwable),
          prevExtractor
        )
      }
    }
  }

  data class SpanStatusData(
    val spanStatusBuilder: SpanStatusBuilder,
    val request: ApplicationRequest,
    val response: ApplicationResponse?,
    val error: Throwable?
  )

  @Deprecated("Please use method `spanKindExtractor`")
  fun setSpanKindExtractor(extractor: (SpanKindExtractor<ApplicationRequest>) -> SpanKindExtractor<ApplicationRequest>) {
    spanKindExtractor { prevSpanKindExtractor ->
      extractor(prevSpanKindExtractor).extract(this)
    }
  }

  fun spanKindExtractor(extract: ApplicationRequest.(SpanKindExtractor<ApplicationRequest>) -> SpanKind) {
    spanKindExtractor = { prevExtractor ->
      SpanKindExtractor<ApplicationRequest> { request: ApplicationRequest ->
        extract(request, prevExtractor)
      }
    }
  }

  @Deprecated("Please use method `attributeExtractor`")
  fun addAttributeExtractor(extractor: AttributesExtractor<in ApplicationRequest, in ApplicationResponse>) {
    attributeExtractor {
      onStart {
        extractor.onStart(attributes, parentContext, request)
      }
      onEnd {
        extractor.onEnd(attributes, parentContext, request, response, error)
      }
    }
  }

  fun attributeExtractor(extractorBuilder: ExtractorBuilder.() -> Unit = {}) {
    val builder = ExtractorBuilder().apply(extractorBuilder).build()
    serverBuilder.addAttributesExtractor(
      object : AttributesExtractor<ApplicationRequest, ApplicationResponse> {
        override fun onStart(attributes: AttributesBuilder, parentContext: Context, request: ApplicationRequest) {
          builder.onStart(OnStartData(attributes, parentContext, request))
        }

        override fun onEnd(attributes: AttributesBuilder, context: Context, request: ApplicationRequest, response: ApplicationResponse?, error: Throwable?) {
          builder.onEnd(OnEndData(attributes, context, request, response, error))
        }
      }
    )
  }

  class ExtractorBuilder {
    private var onStart: OnStartData.() -> Unit = {}
    private var onEnd: OnEndData.() -> Unit = {}

    fun onStart(block: OnStartData.() -> Unit) {
      onStart = block
    }

    fun onEnd(block: OnEndData.() -> Unit) {
      onEnd = block
    }

    internal fun build(): Extractor {
      return Extractor(onStart, onEnd)
    }
  }

  internal class Extractor(val onStart: OnStartData.() -> Unit, val onEnd: OnEndData.() -> Unit)

  data class OnStartData(
    val attributes: AttributesBuilder,
    val parentContext: Context,
    val request: ApplicationRequest
  )

  data class OnEndData(
    val attributes: AttributesBuilder,
    val parentContext: Context,
    val request: ApplicationRequest,
    val response: ApplicationResponse?,
    val error: Throwable?
  )

  @Deprecated(
    "Please use method `capturedRequestHeaders`",
    ReplaceWith("capturedRequestHeaders(headers)")
  )
  fun setCapturedRequestHeaders(headers: List<String>) = capturedRequestHeaders(headers)

  fun capturedRequestHeaders(vararg headers: String) = capturedRequestHeaders(headers.asIterable())

  fun capturedRequestHeaders(headers: Iterable<String>) {
    serverBuilder.setCapturedRequestHeaders(headers.toList())
  }

  @Deprecated(
    "Please use method `capturedResponseHeaders`",
    ReplaceWith("capturedResponseHeaders(headers)")
  )
  fun setCapturedResponseHeaders(headers: List<String>) = capturedResponseHeaders(headers)

  fun capturedResponseHeaders(vararg headers: String) = capturedResponseHeaders(headers.asIterable())

  fun capturedResponseHeaders(headers: Iterable<String>) {
    serverBuilder.setCapturedResponseHeaders(headers.toList())
  }

  @Deprecated(
    "Please use method `knownMethods`",
    ReplaceWith("knownMethods(knownMethods)")
  )
  fun setKnownMethods(knownMethods: Set<String>) = knownMethods(knownMethods)

  fun knownMethods(vararg methods: String) = knownMethods(methods.asIterable())

  fun knownMethods(vararg methods: HttpMethod) = knownMethods(methods.asIterable())

  @JvmName("knownMethodsJvm")
  fun knownMethods(methods: Iterable<HttpMethod>) = knownMethods(methods.map { it.value })

  fun knownMethods(methods: Iterable<String>) {
    methods.toSet().apply {
      serverBuilder.setKnownMethods(this)
    }
  }

  /**
   * {@link #setOpenTelemetry(OpenTelemetry)} sets the serverBuilder to a non-null value.
   */
  internal fun isOpenTelemetryInitialized(): Boolean = this::serverBuilder.isInitialized
}

val KtorServerTracing = createRouteScopedPlugin("OpenTelemetry", ::KtorServerTracingBuilder) {
  require(pluginConfig.isOpenTelemetryInitialized()) { "OpenTelemetry must be set" }

  val contextKey = AttributeKey<Context>("OpenTelemetry")
  val errorKey = AttributeKey<Throwable>("OpenTelemetryException")

  val instrumenter = InstrumenterUtil.buildUpstreamInstrumenter(
    pluginConfig.serverBuilder.instrumenterBuilder(),
    ApplicationRequestGetter,
    pluginConfig.spanKindExtractor(SpanKindExtractor.alwaysServer())
  )

  val tracer = KtorTracer(instrumenter)
  val startPhase = PipelinePhase("OpenTelemetry")

  application.insertPhaseBefore(ApplicationCallPipeline.Monitoring, startPhase)
  application.intercept(startPhase) {
    val context = tracer.start(call)

    if (context != null) {
      call.attributes.put(contextKey, context)
      withContext(context.asContextElement()) {
        try {
          proceed()
        } catch (err: Throwable) {
          // Stash error for reporting later since need ktor to finish setting up the response
          call.attributes.put(errorKey, err)
          throw err
        }
      }
    } else {
      proceed()
    }
  }

  val postSendPhase = PipelinePhase("OpenTelemetryPostSend")
  application.sendPipeline.insertPhaseAfter(ApplicationSendPipeline.After, postSendPhase)
  application.sendPipeline.intercept(postSendPhase) {
    val context = call.attributes.getOrNull(contextKey)
    if (context != null) {
      var error: Throwable? = call.attributes.getOrNull(errorKey)
      try {
        proceed()
      } catch (t: Throwable) {
        error = t
        throw t
      } finally {
        tracer.end(context, call, error)
      }
    } else {
      proceed()
    }
  }

  val callStartedEvent = runCatching {
    RoutingCallStarted // Ktor 2.0
  }.getOrElse {
    // Ktor 3.0
    val routingRoot = Class.forName("io.ktor.server.routing.RoutingRoot")
    val callStartedGetter = routingRoot.getDeclaredMethod("access\$getRoutingCallStarted\$cp")
    callStartedGetter.invoke(null) as EventDefinition<ApplicationCall>
  }

  application.environment.monitor.subscribe(callStartedEvent) { call ->
    HttpServerRoute.update(Context.current(), HttpServerRouteSource.SERVER, { _, arg ->
      runCatching {
        // Ktor 2.0
        (call as RoutingApplicationCall).route.parent.toString()
      }.getOrElse {
        // Ktor 3.0
        val route = arg::class.java.getDeclaredMethod("getRoute").invoke(arg)
        val parent = route::class.java.getDeclaredMethod("getParent").invoke(route)
        parent.toString()
      }
    }, call)
  }
}
