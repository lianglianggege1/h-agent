package com.h.backend.observability.mcp;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservabilityConfig;
import com.h.agent.observability.AgentObservabilityTesting;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 客户端观测契约测试（设计 14.1/19.5）：remote_call Span 挂靠调用线程 current
 * context、执行期间 Header Supplier 注入该 Span 的 W3C traceparent、复用同一
 * McpClient（同一 Supplier）并行两个根 Trace 各自携带正确 Header、无活跃上下文时
 * 注入为空、Authorization 等基础 Header 不被覆盖。
 */
class ObservingMcpToolExecutorTest {

    private AgentObservability observability;
    private InMemorySpanExporter exporter;

    private AgentObservability create() {
        exporter = InMemorySpanExporter.create();
        AgentObservabilityConfig config = AgentObservabilityConfig.builder()
                .baseUrl("http://langfuse.local")
                .publicKey("pk-test")
                .secretKey("sk-test")
                .rootRatio(1.0)
                .scheduleDelayMillis(10)
                .build();
        return AgentObservabilityTesting.build(config, exporter);
    }

    @AfterEach
    void tearDown() {
        if (observability != null) {
            observability.close();
        }
    }

    @Test
    void executorNestsRemoteCallUnderCurrentSpanAndSuppliesW3cDuringExecution() {
        observability = create();
        Map<String, String> capturedHeaders = new HashMap<>();
        ObservingMcpHeadersSupplier supplier =
                new ObservingMcpHeadersSupplier(observability, () -> Map.of("Authorization", "Bearer dev-token"));
        ToolExecutor executor = new ObservingMcpToolExecutor(observability, (request, memoryId) -> {
            capturedHeaders.putAll(supplier.apply(null));
            return "8";
        });

        AgentObservation agent = observability.span(
                ObservationSpec.of("agent general-assistant", HObsKind.AGENT, "langchain4j"),
                observability.currentContext());
        String result;
        try (var ignored = observability.scope(agent.context())) {
            result = executor.execute(request("add", "{\"a\":3,\"b\":5}"), null);
        }
        assertEquals("8", result);
        AgentObservabilityTesting.flush(observability);

        SpanData remoteCall = spanByName("remote_call mcp.tools/call");
        assertEquals(agent.spanId(), remoteCall.getParentSpanId(),
                "remote_call must be a child of the agent span current on the calling thread");
        assertEquals("mcp-client", remoteCall.getAttributes().get(AttributeKey.stringKey(HAttrs.RUNTIME)));
        assertEquals("add", remoteCall.getAttributes().get(AttributeKey.stringKey(HAttrs.TOOL_NAME)));
        assertEquals("success", remoteCall.getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));

        String traceparent = capturedHeaders.get("traceparent");
        assertNotNull(traceparent, "supplier must inject W3C traceparent while the executor scope is open");
        assertEquals(remoteCall.getTraceId(), traceparent.split("-")[1]);
        assertEquals(remoteCall.getSpanId(), traceparent.split("-")[2],
                "traceparent must carry the remote_call span itself, not its parent");
        assertEquals("Bearer dev-token", capturedHeaders.get("Authorization"),
                "base auth headers must survive the W3C merge");
    }

    @Test
    void failureFailsSpanAndKeepsOriginalException() {
        observability = create();
        ToolExecutor executor = new ObservingMcpToolExecutor(observability, (request, memoryId) -> {
            throw new IllegalStateException("tool server error");
        });

        assertThrows(IllegalStateException.class,
                () -> executor.execute(request("add", "{\"a\":1,\"b\":2}"), null));
        AgentObservabilityTesting.flush(observability);

        SpanData remoteCall = spanByName("remote_call mcp.tools/call");
        assertEquals("failure", remoteCall.getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));
    }

    @Test
    void parallelRootTracesThroughSameClientCarryDistinctHeaders() throws Exception {
        observability = create();
        ObservingMcpHeadersSupplier supplier = new ObservingMcpHeadersSupplier(observability, null);
        ToolExecutor executor = new ObservingMcpToolExecutor(observability, (request, memoryId) -> {
            return String.valueOf(supplier.apply(null).get("traceparent"));
        });

        AgentObservation firstRoot = observability.span(
                ObservationSpec.of("agent run one", HObsKind.AGENT, "langchain4j"), observability.currentContext());
        AgentObservation secondRoot = observability.span(
                ObservationSpec.of("agent run two", HObsKind.AGENT, "langchain4j"), observability.currentContext());

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> {
            try (var ignored = observability.scope(firstRoot.context())) {
                return executor.execute(request("add", "{\"a\":1,\"b\":1}"), null);
            }
        });
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> {
            try (var ignored = observability.scope(secondRoot.context())) {
                return executor.execute(request("add", "{\"a\":2,\"b\":2}"), null);
            }
        });
        String firstTraceparent = first.get();
        String secondTraceparent = second.get();
        AgentObservabilityTesting.flush(observability);

        assertNotEquals(firstTraceparent, secondTraceparent,
                "parallel calls through the same McpClient/Supplier must carry distinct trace contexts");
        assertEquals(2, exporter.getFinishedSpanItems().stream()
                        .filter(span -> "remote_call mcp.tools/call".equals(span.getName())).count(),
                "each parallel call creates its own remote_call span");
        assertTrue(exporter.getFinishedSpanItems().stream()
                        .filter(span -> "remote_call mcp.tools/call".equals(span.getName()))
                        .allMatch(span -> List.of(firstRoot.spanId(), secondRoot.spanId())
                                .contains(span.getParentSpanId())),
                "each remote_call must nest under its own root");
    }

    @Test
    void supplierWithoutActiveContextInjectsNothingButKeepsBaseHeaders() {
        observability = create();
        ObservingMcpHeadersSupplier supplier =
                new ObservingMcpHeadersSupplier(observability, () -> Map.of("Authorization", "Bearer dev-token"));

        Map<String, String> headers = supplier.apply(null);

        assertEquals(Map.of("Authorization", "Bearer dev-token"), headers,
                "startup requests (initialize/health) carry auth but no business trace headers");
    }

    @Test
    void supplierDoesNotTouchStandardMcpHeaders() {
        observability = create();
        ObservingMcpHeadersSupplier supplier = new ObservingMcpHeadersSupplier(observability, null);
        AgentObservation span = observability.span(
                ObservationSpec.of("remote_call mcp.tools/call", HObsKind.REMOTE_CALL, "mcp-client"),
                observability.currentContext());

        Map<String, String> headers;
        try (var ignored = observability.scope(span.context())) {
            headers = supplier.apply(null);
        }

        List<String> standard = new ArrayList<>();
        headers.keySet().forEach(name -> {
            if (name.startsWith("Mcp-") || name.equalsIgnoreCase("Content-Type")
                    || name.equalsIgnoreCase("Accept") || name.equalsIgnoreCase("Last-Event-ID")) {
                standard.add(name);
            }
        });
        assertTrue(standard.isEmpty(),
                "supplier must never own standard MCP/HTTP headers owned by the transport: " + standard);
        assertNotNull(headers.get("traceparent"));
    }

    private static ToolExecutionRequest request(String name, String arguments) {
        return ToolExecutionRequest.builder().id("call-1").name(name).arguments(arguments).build();
    }

    private SpanData spanByName(String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span not found: " + name));
    }
}
