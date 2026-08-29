package com.h.agent.observability;

import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.agent.observability.lifecycle.AgentExecutionStart;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.agent.observability.semantic.ArtifactKind;
import com.h.agent.observability.semantic.ArtifactReference;
import com.h.agent.observability.semantic.ArtifactReferenceBlock;
import com.h.agent.observability.semantic.ArtifactUse;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.SemanticMessage;
import com.h.agent.observability.semantic.ToolResultBlock;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CoreObservabilityContractTest {

    private DefaultAgentObservability observability;
    private InMemorySpanExporter exporter;

    private DefaultAgentObservability create(double rootRatio) {
        exporter = InMemorySpanExporter.create();
        AgentObservabilityConfig config = AgentObservabilityConfig.builder()
                .baseUrl("http://langfuse.local")
                .publicKey("pk-test")
                .secretKey("sk-test")
                .rootRatio(rootRatio)
                .scheduleDelayMillis(10)
                .build();
        return DefaultAgentObservability.buildWithExporter(config, exporter);
    }

    @AfterEach
    void tearDown() {
        if (observability != null) {
            observability.close();
        }
    }

    private AgentExecutionStart executionStart() {
        return new AgentExecutionStart(
                "agent.run", "session-1", 42L, "general-assistant", "as-1", "CHAT", "run-1",
                List.of("smoke", "contract"), Map.of(),
                SemanticContent.ofMessages(List.of(SemanticMessage.of("user", "hello"))));
    }

    @Test
    void rootExecutionFormsCausalTreeWithSemanticContentAndLangfuseAttributes() throws Exception {
        observability = create(1.0);
        AgentExecutionObservation root = observability.start(executionStart());
        assertNotNull(root.traceId());

        String agentSpanId;
        try (AutoCloseable scope = root.scope()) {
            AgentObservation agent = observability.span(
                    ObservationSpec.of("agent general-assistant", HObsKind.AGENT, "langchain4j"),
                    observability.currentContext());
            agentSpanId = agent.spanId();
            agent.attribute("h.agent_id", "general-assistant");
            try (AgentObservation agentClose = agent) {
                AgentObservation generation = observability.span(
                        ObservationSpec.of("gen_ai.gpt-4o", HObsKind.GENERATION, "langchain4j"),
                        agent.context());
                generation.input(SemanticContent.ofMessages(List.of(SemanticMessage.of("user", "hi"))));
                generation.attribute("gen_ai.request.model", "gpt-4o");
                generation.output(SemanticContent.ofMessages(List.of(SemanticMessage.of("assistant", "hi there"))));
                generation.succeed();

                AgentObservation tool = observability.span(
                        ObservationSpec.of("tool search", HObsKind.TOOL, "langchain4j",
                                Map.of("h.tool_name", "search")),
                        agent.context());
                tool.input(SemanticContent.ofMessages(List.of(SemanticMessage.of("tool", "query"))));
                tool.output(SemanticContent.ofMessages(List.of(SemanticMessage.of("tool", "result"))));
                tool.succeed();
            }
            assertEquals(root.traceId(), agent.traceId());
        }
        root.succeed(SemanticContent.ofMessages(List.of(SemanticMessage.of("assistant", "final answer"))));
        observability.flushForTest();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(4, spans.size());
        SpanData rootSpan = spanByName(spans, "agent.run");
        SpanData agentSpan = spanByName(spans, "agent general-assistant");
        SpanData generationSpan = spanByName(spans, "gen_ai.gpt-4o");
        SpanData toolSpan = spanByName(spans, "tool search");

        assertEquals(rootSpan.getSpanId(), agentSpan.getParentSpanId());
        assertEquals(agentSpan.getSpanId(), generationSpan.getParentSpanId());
        assertEquals(agentSpan.getSpanId(), toolSpan.getParentSpanId());
        assertEquals(root.traceId(), rootSpan.getTraceId());
        assertEquals(root.traceId(), generationSpan.getTraceId());
        assertEquals(agentSpanId, agentSpan.getSpanId());

        assertEquals("session-1", rootSpan.getAttributes().get(AttributeKey.stringKey("langfuse.session.id")));
        assertEquals("42", rootSpan.getAttributes().get(AttributeKey.stringKey("langfuse.user.id")));
        assertEquals("run-1", rootSpan.getAttributes().get(AttributeKey.stringKey("h.root_run_id")));
        assertEquals("success", rootSpan.getAttributes().get(AttributeKey.stringKey("h.outcome")));
        assertEquals("1", rootSpan.getAttributes().get(AttributeKey.stringKey("h.schema_version")));
        assertTrue(rootSpan.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")).contains("hello"));
        assertTrue(rootSpan.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")).contains("final answer"));
        assertEquals("tool", toolSpan.getAttributes().get(AttributeKey.stringKey("h.kind")));
        assertEquals("generation", generationSpan.getAttributes().get(AttributeKey.stringKey("h.kind")));
        assertTrue(generationSpan.getAttributes().get(AttributeKey.stringKey("langfuse.observation.input")).contains("hi"));
        assertTrue(generationSpan.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output")).contains("hi there"));
    }

    @Test
    void usageWritesSemanticAttributesAndLangfuseUsageDetails() {
        observability = create(1.0);
        AgentExecutionObservation root = observability.start(executionStart());
        AgentObservation generation = observability.span(
                ObservationSpec.of("gen_ai.gpt-4o", HObsKind.GENERATION, "langchain4j"),
                root.observationContext());
        generation.usage(120, 80, 200);
        generation.succeed();
        root.succeed(null);
        observability.flushForTest();

        SpanData span = spanByName(exporter.getFinishedSpanItems(), "gen_ai.gpt-4o");
        assertEquals(120L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.prompt_tokens")));
        assertEquals(80L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.completion_tokens")));
        assertEquals(200L, span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.total_tokens")));
        // Langfuse 4 对自定义 scope 只把 usage_details JSON 映射进 usage 字段。
        assertEquals("{\"prompt_tokens\":120,\"completion_tokens\":80,\"total_tokens\":200}",
                span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.usage_details")));
    }

    @Test
    void partialUsageFallsBackToCanonicalRawKeys() {
        observability = create(1.0);
        AgentExecutionObservation root = observability.start(executionStart());
        AgentObservation generation = observability.span(
                ObservationSpec.of("gen_ai.gpt-4o", HObsKind.GENERATION, "langchain4j"),
                root.observationContext());
        generation.usage(120, 80, null);
        generation.succeed();
        root.succeed(null);
        observability.flushForTest();

        SpanData span = spanByName(exporter.getFinishedSpanItems(), "gen_ai.gpt-4o");
        assertEquals("{\"input\":120,\"output\":80}",
                span.getAttributes().get(AttributeKey.stringKey("langfuse.observation.usage_details")));
        assertNull(span.getAttributes().get(AttributeKey.longKey("gen_ai.usage.total_tokens")));
    }

    @Test
    void executionTerminalStateIsFirstWins() {
        observability = create(1.0);
        AgentExecutionObservation root = observability.start(executionStart());
        root.succeed(null);
        root.fail(new RuntimeException("boom"));
        root.cancel("late");
        observability.flushForTest();

        List<SpanData> spans = exporter.getFinishedSpanItems();
        assertEquals(1, spans.size());
        SpanData span = spans.get(0);
        assertEquals("success", span.getAttributes().get(AttributeKey.stringKey("h.outcome")));
        assertFalse(span.getAttributes().asMap().containsKey("exception.type"));
    }

    @Test
    void failureRecordsExceptionAndOutcome() {
        observability = create(1.0);
        AgentExecutionObservation root = observability.start(executionStart());
        root.fail(new IllegalStateException("kaputt"));
        observability.flushForTest();

        SpanData span = exporter.getFinishedSpanItems().get(0);
        assertEquals("failure", span.getAttributes().get(AttributeKey.stringKey("h.outcome")));
        assertEquals("java.lang.IllegalStateException",
                span.getAttributes().get(AttributeKey.stringKey("exception.type")));
    }

    @Test
    void noopImplementationNeverThrows() {
        AgentObservability noop = AgentObservabilitySdk.build(AgentObservabilityConfig.builder().build());
        assertFalse(noop.enabled());
        assertEquals(LangfuseRuntimeStatus.DISABLED_NOT_CONFIGURED, noop.status());

        AgentExecutionObservation execution = noop.start(null);
        assertNull(execution.traceId());
        execution.succeed(null);
        execution.fail(new RuntimeException());
        execution.cancel("x");
        execution.close();

        AgentObservation observation = noop.span(null, null);
        observation.attribute("k", "v");
        observation.input(null);
        observation.output(null);
        observation.succeed();
        observation.fail(null);
        observation.cancel("x");
        observation.close();

        noop.inject(null, null);
        ObservationContext ignored = noop.extract(null);
        noop.close();
    }

    @Test
    void partialConfigurationIsDegradedNoop() {
        AgentObservability degraded = AgentObservabilitySdk.build(AgentObservabilityConfig.builder()
                .baseUrl("http://langfuse.local")
                .publicKey("pk-only")
                .build());
        assertFalse(degraded.enabled());
        assertEquals(LangfuseRuntimeStatus.DEGRADED_MISCONFIGURED, degraded.status());
    }

    @Test
    void foreignInstrumentationScopeIsFiltered() throws Exception {
        InMemorySpanExporter foreignExporter = InMemorySpanExporter.create();
        SdkTracerProvider provider = SdkTracerProvider.builder()
                .addSpanProcessor(new ScopeFilteringSpanProcessor(SimpleSpanProcessor.create(foreignExporter)))
                .build();
        provider.get(AgentObservability.INSTRUMENTATION_SCOPE).spanBuilder("h-span").startSpan().end();
        provider.get("io.opentelemetry.spring-web").spanBuilder("foreign-span").startSpan().end();

        assertEquals(1, foreignExporter.getFinishedSpanItems().size());
        assertEquals("h-span", foreignExporter.getFinishedSpanItems().get(0).getName());
        provider.shutdown().join(5, TimeUnit.SECONDS);
    }

    @Test
    void parentBasedSamplingDropsWholeTree() throws Exception {
        observability = create(0.0);
        AgentExecutionObservation root = observability.start(executionStart());
        // Unsampled roots still expose a valid trace id (design §18); only export count proves sampling.
        try (AutoCloseable scope = root.scope()) {
            AgentObservation tool = observability.span(
                    ObservationSpec.of("tool search", HObsKind.TOOL, "langchain4j"),
                    observability.currentContext());
            tool.succeed();
        }
        root.succeed(null);
        observability.flushForTest();
        assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }

    @Test
    void w3cPropagationRoundTripsAcrossProcessBoundary() {
        observability = create(1.0);
        AgentExecutionObservation root = observability.start(executionStart());

        Map<String, String> headers = new HashMap<>();
        observability.inject(root.observationContext(), headers);
        assertTrue(headers.containsKey("traceparent"));
        assertTrue(headers.containsKey("baggage"));

        ObservationContext extracted = observability.extract(headers);
        AgentObservation server = observability.span(
                ObservationSpec.of("remote_call a2a.message/send", HObsKind.REMOTE_CALL, "a2a-server"),
                extracted);
        assertEquals(root.traceId(), server.traceId());
        server.succeed();
        root.succeed(null);
        observability.flushForTest();

        SpanData remote = spanByName(exporter.getFinishedSpanItems(), "remote_call a2a.message/send");
        assertEquals(root.traceId(), remote.getTraceId());
    }

    @Test
    void remoteCallSpecUsesClientSpanKind() {
        observability = create(1.0);
        AgentExecutionObservation root = observability.start(executionStart());
        AgentObservation remote = observability.span(
                ObservationSpec.of("remote_call mcp.tools/call", HObsKind.REMOTE_CALL, "mcp-client"),
                root.observationContext());
        remote.succeed();
        root.succeed(null);
        observability.flushForTest();

        SpanData span = spanByName(exporter.getFinishedSpanItems(), "remote_call mcp.tools/call");
        assertEquals(io.opentelemetry.api.trace.SpanKind.CLIENT, span.getKind());
    }

    @Test
    void artifactReferenceEncodesAsBoundedSmallJsonWithoutBinaryOrStorageLocation() {
        observability = create(1.0);
        AgentExecutionObservation root = observability.start(executionStart());
        AgentObservation tool = observability.span(
                ObservationSpec.of("tool send_file_to_chat", HObsKind.TOOL, "langchain4j",
                        Map.of("h.tool_name", "send_file_to_chat")),
                root.observationContext());
        tool.output(SemanticContent.ofBlocks(List.of(
                new ToolResultBlock("call-1", "send_file_to_chat", "文件已发送到聊天中。", false),
                new ArtifactReferenceBlock(ArtifactReference.builder()
                        .resourceId("res-1")
                        .kind(ArtifactKind.IMAGE)
                        .use(ArtifactUse.TOOL_OUTPUT)
                        .businessRole("GENERATED")
                        .mimeType("image/png")
                        .byteSize(1024L)
                        .width(32)
                        .height(32)
                        .fileName("dot.png")
                        .applicationViewUrl("/api/chat/resources/res-1/content")
                        .build()))));
        tool.succeed();
        root.succeed(null);
        observability.flushForTest();

        SpanData toolSpan = spanByName(exporter.getFinishedSpanItems(), "tool send_file_to_chat");
        String output = toolSpan.getAttributes().get(AttributeKey.stringKey("langfuse.observation.output"));
        assertTrue(output.contains("\"type\":\"artifact\""));
        assertTrue(output.contains("\"resource_id\":\"res-1\""));
        assertTrue(output.contains("\"use\":\"TOOL_OUTPUT\""));
        assertTrue(output.contains("\"kind\":\"IMAGE\""));
        assertTrue(output.contains("\"byte_size\":1024"));
        assertFalse(output.matches("(?s).*[A-Za-z0-9+/]{200,}={0,2}.*"), "no base64 payload allowed");
        assertFalse(output.contains("storage_key"), "no storage location allowed");
        assertTrue(output.length() < 2048, "artifact reference must stay a small bounded JSON");
    }

    private SpanData spanByName(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span not found: " + name));
    }
}
