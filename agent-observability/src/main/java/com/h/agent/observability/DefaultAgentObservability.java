package com.h.agent.observability;

import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.agent.observability.lifecycle.AgentExecutionStart;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.agent.observability.lifecycle.ObservationMetadata;
import com.h.agent.observability.semantic.ContentCaptureMode;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.SemanticJson;
import io.opentelemetry.api.baggage.Baggage;
import io.opentelemetry.api.baggage.BaggageBuilder;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanBuilder;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapPropagator;
import io.opentelemetry.context.propagation.TextMapSetter;
import io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

final class DefaultAgentObservability implements AgentObservability {

    private static final Logger LOG = Logger.getLogger(DefaultAgentObservability.class.getName());

    private static final ThreadLocal<ObservationContext> CURRENT = new ThreadLocal<>();

    private static final TextMapSetter<Map<String, String>> MAP_SETTER = (carrier, key, value) -> {
        if (carrier != null && key != null && value != null) {
            carrier.put(key, value);
        }
    };

    private static final TextMapGetter<Map<String, String>> MAP_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Map<String, String> carrier) {
            return carrier == null ? List.of() : carrier.keySet();
        }

        @Override
        public String get(Map<String, String> carrier, String key) {
            return carrier == null ? null : carrier.get(key);
        }
    };

    private final OpenTelemetrySdk openTelemetry;
    private final SdkTracerProvider tracerProvider;
    private final io.opentelemetry.api.trace.Tracer tracer;
    private final AgentObservabilityConfig config;
    private final SemanticJson json;
    private final TextMapPropagator propagator;

    private DefaultAgentObservability(OpenTelemetrySdk openTelemetry,
                                      SdkTracerProvider tracerProvider,
                                      AgentObservabilityConfig config) {
        this.openTelemetry = openTelemetry;
        this.tracerProvider = tracerProvider;
        this.tracer = openTelemetry.getTracer(INSTRUMENTATION_SCOPE);
        this.config = config;
        this.json = new SemanticJson(config.limits());
        this.propagator = openTelemetry.getPropagators().getTextMapPropagator();
    }

    static AgentObservability build(AgentObservabilityConfig config) {
        String endpoint = normalizeBaseUrl(config.baseUrl()) + "/api/public/otel/v1/traces";
        String credentials = config.publicKey() + ":" + config.secretKey();
        String basicAuth = java.util.Base64.getEncoder()
                .encodeToString(credentials.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        OtlpHttpSpanExporter exporter = OtlpHttpSpanExporter.builder()
                .setEndpoint(endpoint)
                .setTimeout(Duration.ofMillis(config.timeoutMillis()))
                .addHeader("Authorization", "Basic " + basicAuth)
                .addHeader("x-langfuse-ingestion-version", "4")
                .build();

        return buildWithExporter(config, exporter);
    }

    static DefaultAgentObservability buildWithExporter(AgentObservabilityConfig config,
                                                       io.opentelemetry.sdk.trace.export.SpanExporter exporter) {
        BatchSpanProcessor batchProcessor = BatchSpanProcessor.builder(exporter)
                .setMaxQueueSize(config.queueSize())
                .setMaxExportBatchSize(Math.min(config.batchSize(), config.queueSize()))
                .setScheduleDelay(Duration.ofMillis(config.scheduleDelayMillis()))
                .setExporterTimeout(Duration.ofMillis(config.timeoutMillis()))
                .build();

        Resource resource = Resource.getDefault().merge(Resource.builder()
                .put("service.name", config.serviceName())
                .put("service.version", config.serviceVersion())
                .put("deployment.environment.name", config.environment())
                .build());

        Sampler sampler = Sampler.parentBased(Sampler.traceIdRatioBased(config.rootRatio()));

        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .setResource(resource)
                .setSampler(sampler)
                .addSpanProcessor(new ScopeFilteringSpanProcessor(batchProcessor))
                .build();

        OpenTelemetrySdk sdk = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.create(TextMapPropagator.composite(
                        W3CTraceContextPropagator.getInstance(),
                        W3CBaggagePropagator.getInstance())))
                .build();

        return new DefaultAgentObservability(sdk, tracerProvider, config);
    }

    private static String normalizeBaseUrl(String baseUrl) {
        String trimmed = baseUrl == null ? "" : baseUrl.trim();
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public LangfuseRuntimeStatus status() {
        return LangfuseRuntimeStatus.ACTIVE;
    }

    @Override
    public AgentExecutionObservation start(AgentExecutionStart start) {
        return startExecution(start, null);
    }

    @Override
    public AgentExecutionObservation startMaintenance(AgentExecutionStart start, ObservationContext linkedPrimary) {
        return startExecution(start, linkedPrimary);
    }

    private AgentExecutionObservation startExecution(AgentExecutionStart start, ObservationContext linkedPrimary) {
        if (start == null) {
            return NoopAgentObservability.noopExecution();
        }
        ObservationMetadata metadata = new ObservationMetadata(
                start.sessionId(),
                start.userId() == null ? null : String.valueOf(start.userId()),
                start.traceName(),
                start.tags() == null ? List.of() : start.tags(),
                start.rootRunId(),
                start.entryKind(),
                start.agentSessionId()
        );
        String spanName = start.traceName() == null || start.traceName().isBlank()
                ? "agent.run"
                : start.traceName();
        SpanBuilder builder = tracer.spanBuilder(spanName)
                .setSpanKind(SpanKind.INTERNAL);
        applyMetadata(builder, metadata);
        builder.setAttribute(HAttrs.SCHEMA_VERSION, "1");
        builder.setAttribute(HAttrs.KIND, HObsKind.AGENT.name().toLowerCase());
        builder.setAttribute(HAttrs.LANGFUSE_OBSERVATION_TYPE, "span");
        builder.setAttribute(HAttrs.RUNTIME, "product");
        if (start.agentId() != null) {
            builder.setAttribute(HAttrs.AGENT_ID, start.agentId());
        }
        applyStartAttributes(builder, start.attributes());
        if (linkedPrimary != null && linkedPrimary.otelContext() != null) {
            io.opentelemetry.api.trace.SpanContext linked =
                    Span.fromContext(linkedPrimary.otelContext()).getSpanContext();
            if (linked.isValid()) {
                builder.addLink(linked);
            }
        }
        Span span = builder.startSpan();
        recordInput(span, start.input());
        Context otelContext = span.storeInContext(Context.current());
        otelContext = withBaggage(otelContext, metadata);
        ObservationContext context = ObservationContext.of(otelContext, metadata);
        return new DefaultAgentExecutionObservation(span, context, this);
    }

    private static final class ScopeHolder implements com.h.agent.observability.lifecycle.ObservationScope {

        private final ObservationContext previous;
        private final io.opentelemetry.context.Scope otelScope;

        ScopeHolder(ObservationContext current) {
            this.previous = CURRENT.get();
            this.otelScope = current.otelContext().makeCurrent();
            CURRENT.set(current);
        }

        @Override
        public void close() {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
            otelScope.close();
        }
    }

    private Context withBaggage(Context context, ObservationMetadata metadata) {
        BaggageBuilder baggage = Baggage.builder();
        if (metadata.sessionId() != null) {
            baggage.put(HAttrs.BAGGAGE_SESSION_ID, metadata.sessionId());
        }
        if (metadata.userId() != null) {
            baggage.put(HAttrs.BAGGAGE_USER_ID, metadata.userId());
        }
        if (metadata.traceName() != null) {
            baggage.put(HAttrs.BAGGAGE_TRACE_NAME, metadata.traceName());
        }
        if (metadata.tags() != null && !metadata.tags().isEmpty()) {
            baggage.put(HAttrs.BAGGAGE_TRACE_TAGS, String.join(",", metadata.tags()));
        }
        return baggage.build().storeInContext(context);
    }

    @Override
    public AgentObservation span(ObservationSpec spec, ObservationContext parent) {
        if (spec == null) {
            return NoopAgentObservability.noopObservation();
        }
        ObservationContext effectiveParent = parent != null ? parent : currentContext();
        SpanBuilder builder = tracer.spanBuilder(spec.name())
                .setSpanKind(spec.kind() == HObsKind.REMOTE_CALL ? SpanKind.CLIENT : SpanKind.INTERNAL);
        if (effectiveParent != null && effectiveParent.otelContext() != null) {
            builder.setParent(effectiveParent.otelContext());
        }
        ObservationMetadata metadata = effectiveParent == null
                ? ObservationMetadata.empty()
                : effectiveParent.metadata();
        applyMetadata(builder, metadata);
        builder.setAttribute(HAttrs.SCHEMA_VERSION, "1");
        builder.setAttribute(HAttrs.KIND, spec.kind().name().toLowerCase());
        builder.setAttribute(HAttrs.LANGFUSE_OBSERVATION_TYPE, langfuseObservationType(spec.kind()));
        if (spec.runtime() != null) {
            builder.setAttribute(HAttrs.RUNTIME, spec.runtime());
        }
        applyStartAttributes(builder, spec.attributes());
        builder.setAttribute(HAttrs.CONTENT_CAPTURE_MODE, config.contentMode().name());
        Span span = builder.startSpan();
        ObservationContext context = ObservationContext.of(
                span.storeInContext(effectiveParent == null ? Context.current() : effectiveParent.otelContext()),
                metadata);
        return new DefaultAgentObservation(span, context, this);
    }

    private static String langfuseObservationType(HObsKind kind) {
        return kind == HObsKind.GENERATION ? "generation" : "span";
    }

    private void applyMetadata(SpanBuilder builder, ObservationMetadata metadata) {
        if (metadata.sessionId() != null) {
            builder.setAttribute(HAttrs.LANGFUSE_SESSION_ID, metadata.sessionId());
        }
        if (metadata.userId() != null) {
            builder.setAttribute(HAttrs.LANGFUSE_USER_ID, metadata.userId());
        }
        if (metadata.traceName() != null) {
            builder.setAttribute(HAttrs.LANGFUSE_TRACE_NAME, metadata.traceName());
        }
        if (metadata.tags() != null && !metadata.tags().isEmpty()) {
            builder.setAttribute(HAttrs.LANGFUSE_TRACE_TAGS, String.join(",", metadata.tags()));
        }
        if (metadata.agentSessionId() != null) {
            builder.setAttribute(HAttrs.AGENT_SESSION_ID, metadata.agentSessionId());
        }
        if (metadata.rootRunId() != null) {
            builder.setAttribute(HAttrs.ROOT_RUN_ID, metadata.rootRunId());
        }
        if (metadata.entryKind() != null) {
            builder.setAttribute(HAttrs.ENTRY_KIND, metadata.entryKind());
        }
    }

    private void applyStartAttributes(SpanBuilder builder, Map<String, String> attributes) {
        if (attributes == null) {
            return;
        }
        attributes.forEach((key, value) -> {
            if (key != null && value != null) {
                builder.setAttribute(key, value);
            }
        });
    }

    @Override
    public ObservationContext currentContext() {
        ObservationContext current = CURRENT.get();
        if (current != null) {
            return current;
        }
        return ObservationContext.of(Context.current(), ObservationMetadata.empty());
    }

    @Override
    public com.h.agent.observability.lifecycle.ObservationScope scope(ObservationContext context) {
        if (context == null) {
            return () -> {
            };
        }
        return new ScopeHolder(context);
    }

    @Override
    public ObservationContext extract(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) {
            return ObservationContext.root();
        }
        Context extracted = propagator.extract(Context.current(), new HashMap<>(headers), MAP_GETTER);
        Baggage baggage = Baggage.fromContext(extracted);
        String sessionId = baggage.getEntryValue(HAttrs.BAGGAGE_SESSION_ID);
        String userId = baggage.getEntryValue(HAttrs.BAGGAGE_USER_ID);
        String traceName = baggage.getEntryValue(HAttrs.BAGGAGE_TRACE_NAME);
        String tags = baggage.getEntryValue(HAttrs.BAGGAGE_TRACE_TAGS);
        ObservationMetadata metadata = new ObservationMetadata(
                sessionId,
                userId,
                traceName,
                tags == null || tags.isBlank() ? List.of() : List.of(tags.split(",")),
                null,
                null,
                null
        );
        return ObservationContext.of(extracted, metadata);
    }

    @Override
    public void inject(ObservationContext context, Map<String, String> headers) {
        if (context == null || headers == null) {
            return;
        }
        propagator.inject(context.otelContext(), headers, MAP_SETTER);
    }

    void recordInput(Span span, SemanticContent content) {
        if (content == null || config.contentMode() == ContentCaptureMode.METADATA_ONLY) {
            return;
        }
        String encoded = json.encode(content);
        if (encoded != null) {
            span.setAttribute(HAttrs.INPUT, encoded);
            span.setAttribute(HAttrs.CONTENT_CAPTURE_STATE, json.stateOf(content).name());
        }
    }

    void recordOutput(Span span, SemanticContent content) {
        if (content == null || config.contentMode() == ContentCaptureMode.METADATA_ONLY) {
            return;
        }
        String encoded = json.encode(content);
        if (encoded != null) {
            span.setAttribute(HAttrs.OUTPUT, encoded);
            span.setAttribute(HAttrs.CONTENT_CAPTURE_STATE, json.stateOf(content).name());
        }
    }

    void flushForTest() {
        tracerProvider.forceFlush().join(Math.max(1, config.shutdownTimeoutMillis()), TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        try {
            CompletableResultCode flush = tracerProvider.forceFlush();
            flush.join(Math.max(1, config.shutdownTimeoutMillis()), TimeUnit.MILLISECONDS);
            CompletableResultCode shutdown = tracerProvider.shutdown();
            shutdown.join(Math.max(1, config.shutdownTimeoutMillis()), TimeUnit.MILLISECONDS);
        } catch (RuntimeException ex) {
            LOG.warning("Observability shutdown flush failed: " + ex.getMessage());
        }
    }

    private static final class DefaultAgentExecutionObservation implements AgentExecutionObservation {

        private final Span span;
        private final ObservationContext context;
        private final DefaultAgentObservability owner;
        private boolean terminal;

        DefaultAgentExecutionObservation(Span span, ObservationContext context,
                                         DefaultAgentObservability owner) {
            this.span = span;
            this.context = context;
            this.owner = owner;
        }

        @Override
        public String traceId() {
            return span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null;
        }

        @Override
        public ObservationContext observationContext() {
            return context;
        }

        @Override
        public com.h.agent.observability.lifecycle.ObservationScope scope() {
            return new ScopeHolder(context);
        }

        @Override
        public void succeed(SemanticContent output) {
            if (!markTerminal()) {
                return;
            }
            span.setAttribute(HAttrs.OUTCOME, "success");
            owner.recordOutput(span, output);
            span.end();
        }

        @Override
        public void fail(Throwable error) {
            if (!markTerminal()) {
                return;
            }
            span.setAttribute(HAttrs.OUTCOME, "failure");
            if (error != null) {
                span.recordException(error);
                span.setAttribute("exception.type", error.getClass().getName());
                span.setAttribute("exception.message", error.getMessage());
                span.setStatus(StatusCode.ERROR, error.getMessage());
            } else {
                span.setStatus(StatusCode.ERROR);
            }
            span.end();
        }

        @Override
        public void cancel(String reason) {
            if (!markTerminal()) {
                return;
            }
            span.setAttribute(HAttrs.OUTCOME, "cancelled");
            if (reason != null) {
                span.setAttribute("h.cancel_reason", reason);
            }
            span.end();
        }

        @Override
        public void close() {
            if (markTerminal()) {
                span.end();
            }
        }

        private synchronized boolean markTerminal() {
            if (terminal) {
                return false;
            }
            terminal = true;
            return true;
        }
    }

    private static final class DefaultAgentObservation implements AgentObservation {

        private final Span span;
        private final ObservationContext context;
        private final DefaultAgentObservability owner;
        private boolean terminal;

        DefaultAgentObservation(Span span, ObservationContext context,
                                DefaultAgentObservability owner) {
            this.span = span;
            this.context = context;
            this.owner = owner;
        }

        @Override
        public String traceId() {
            return span.getSpanContext().isValid() ? span.getSpanContext().getTraceId() : null;
        }

        @Override
        public String spanId() {
            return span.getSpanContext().isValid() ? span.getSpanContext().getSpanId() : null;
        }

        @Override
        public ObservationContext context() {
            return context;
        }

        @Override
        public void attribute(String key, String value) {
            if (key != null && value != null) {
                span.setAttribute(key, value);
            }
        }

        @Override
        public void attribute(String key, long value) {
            if (key != null) {
                span.setAttribute(key, value);
            }
        }

        @Override
        public void usage(Number inputTokens, Number outputTokens, Number totalTokens) {
            if (inputTokens == null && outputTokens == null && totalTokens == null) {
                return;
            }
            Long input = inputTokens == null ? null : inputTokens.longValue();
            Long output = outputTokens == null ? null : outputTokens.longValue();
            Long total = totalTokens == null ? null : totalTokens.longValue();
            if (input != null) {
                span.setAttribute(HAttrs.GEN_AI_USAGE_PROMPT_TOKENS, input);
            }
            if (output != null) {
                span.setAttribute(HAttrs.GEN_AI_USAGE_COMPLETION_TOKENS, output);
            }
            if (total != null) {
                span.setAttribute(HAttrs.GEN_AI_USAGE_TOTAL_TOKENS, total);
            }
            span.setAttribute(HAttrs.LANGFUSE_OBSERVATION_USAGE_DETAILS,
                    usageDetailsJson(input, output, total));
        }

        /**
         * Langfuse UsageDetails 联合校验：三键齐全时 OpenAI completion 格式会被映射为
         * 标准 input/output/total 用量类型（与默认模型定价匹配）；缺失任一键则退化为
         * Raw 格式，此时直接用标准类型名作 key 才能保持同样的映射结果。
         */
        private static String usageDetailsJson(Long input, Long output, Long total) {
            StringBuilder json = new StringBuilder("{");
            if (input != null && output != null && total != null) {
                json.append("\"prompt_tokens\":").append(input)
                        .append(",\"completion_tokens\":").append(output)
                        .append(",\"total_tokens\":").append(total);
            } else {
                if (input != null) {
                    json.append("\"input\":").append(input);
                }
                if (output != null) {
                    if (json.length() > 1) {
                        json.append(',');
                    }
                    json.append("\"output\":").append(output);
                }
                if (total != null) {
                    if (json.length() > 1) {
                        json.append(',');
                    }
                    json.append("\"total\":").append(total);
                }
            }
            return json.append('}').toString();
        }

        @Override
        public void input(SemanticContent content) {
            owner.recordInput(span, content);
        }

        @Override
        public void output(SemanticContent content) {
            owner.recordOutput(span, content);
        }

        @Override
        public void succeed() {
            if (!markTerminal()) {
                return;
            }
            span.setAttribute(HAttrs.OUTCOME, "success");
            span.end();
        }

        @Override
        public void fail(Throwable error) {
            if (!markTerminal()) {
                return;
            }
            span.setAttribute(HAttrs.OUTCOME, "failure");
            if (error != null) {
                span.recordException(error);
                span.setAttribute("exception.type", error.getClass().getName());
                span.setAttribute("exception.message", error.getMessage());
                span.setStatus(StatusCode.ERROR, error.getMessage());
            } else {
                span.setStatus(StatusCode.ERROR);
            }
            span.end();
        }

        @Override
        public void cancel(String reason) {
            if (!markTerminal()) {
                return;
            }
            span.setAttribute(HAttrs.OUTCOME, "cancelled");
            if (reason != null) {
                span.setAttribute("h.cancel_reason", reason);
            }
            span.end();
        }

        @Override
        public void close() {
            if (markTerminal()) {
                span.end();
            }
        }

        private synchronized boolean markTerminal() {
            if (terminal) {
                return false;
            }
            terminal = true;
            return true;
        }
    }
}
