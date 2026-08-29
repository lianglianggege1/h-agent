package com.h.otheragents.observability;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservabilityConfig;
import com.h.agent.observability.AgentObservabilityTesting;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.otheragents.mcp.AdditionMcpTool;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpLoggableSession;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.LoggingLevel;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import reactor.core.publisher.Mono;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 服务端 Tool Observation 契约测试（设计 14.2/19.5）：工具 Observation 父级
 * 来自当次 POST 的 SERVER remote_call（经官方 McpTransportContext 通道，跨
 * boundedElastic 线程切换不丢失）、真实 ToolCallback 结果语义不变、完成/异常
 * 恰好结束一次。
 */
class ObservedMcpToolSpecificationsTest {

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
    void toolObservationNestsUnderServerSpanAcrossThreadHop() {
        observability = create();
        AgentObservation server = observability.span(
                ObservationSpec.of("remote_call mcp.server", HObsKind.REMOTE_CALL, "mcp-server"),
                observability.currentContext());

        McpServerFeatures.AsyncToolSpecification specification =
                ObservedMcpToolSpecifications.observing(observability, syncSpecification(new AdditionMcpTool()));

        McpSchema.CallToolResult result = specification.callHandler()
                .apply(exchangeWith(server.context()),
                        new McpSchema.CallToolRequest("add_numbers", Map.of("a", 3, "b", 5)))
                .block();
        AgentObservabilityTesting.flush(observability);

        String text = result.content().get(0).toString();
        assertTrue(text.contains("8"), "real tool result must be preserved, got: " + text);

        SpanData tool = spanByName("tool add_numbers");
        assertEquals(server.spanId(), tool.getParentSpanId(),
                "tool observation must nest under the server remote_call across the boundedElastic thread hop");
        assertEquals(server.traceId(), tool.getTraceId());
        assertEquals("mcp-server", tool.getAttributes().get(AttributeKey.stringKey(HAttrs.RUNTIME)));
        assertEquals("add_numbers", tool.getAttributes().get(AttributeKey.stringKey(HAttrs.TOOL_NAME)));
        assertEquals("success", tool.getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));
    }

    @Test
    void toolWithoutTransportContextBecomesOwnRoot() {
        observability = create();
        McpServerFeatures.AsyncToolSpecification specification =
                ObservedMcpToolSpecifications.observing(observability, syncSpecification(new AdditionMcpTool()));

        specification.callHandler()
                .apply(exchangeWith(null),
                        new McpSchema.CallToolRequest("add_numbers", Map.of("a", 1, "b", 2)))
                .block();
        AgentObservabilityTesting.flush(observability);

        SpanData tool = spanByName("tool add_numbers");
        assertEquals("0000000000000000", tool.getParentSpanId(),
                "without a traced POST the tool observation starts at its own root");
    }

    @Test
    void toolErrorFailsObservationAndPropagatesOriginalException() {
        observability = create();
        McpServerFeatures.AsyncToolSpecification specification = ObservedMcpToolSpecifications.observing(
                observability,
                McpServerFeatures.SyncToolSpecification.builder()
                        .tool(McpSchema.Tool.builder().name("boom").description("always fails").build())
                        .callHandler((exchange, request) -> {
                            throw new IllegalStateException("tool blew up");
                        })
                        .build());

        assertThrows(IllegalStateException.class, () -> specification.callHandler()
                .apply(exchangeWith(null), new McpSchema.CallToolRequest("boom", Map.of()))
                .block());
        AgentObservabilityTesting.flush(observability);

        assertEquals("failure", spanByName("tool boom")
                .getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));
    }

    @Test
    void observingKeepsRealToolCallbackConversion() {
        observability = create();
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(new AdditionMcpTool())
                .build()
                .getToolCallbacks();

        java.util.List<McpServerFeatures.AsyncToolSpecification> specifications =
                ObservedMcpToolSpecifications.observing(observability, callbacks);

        assertEquals(1, specifications.size());
        assertEquals("add_numbers", specifications.get(0).tool().name(),
                "tool schema (name/description) must survive the observing conversion");
    }

    private static McpServerFeatures.SyncToolSpecification syncSpecification(Object toolObject) {
        ToolCallback[] callbacks = MethodToolCallbackProvider.builder()
                .toolObjects(toolObject)
                .build()
                .getToolCallbacks();
        return org.springframework.ai.mcp.McpToolUtils.toSyncToolSpecification(callbacks[0]);
    }

    private static McpAsyncServerExchange exchangeWith(ObservationContext serverContext) {
        McpTransportContext transportContext = serverContext == null
                ? McpTransportContext.EMPTY
                : McpTransportContext.create(Map.of(McpObservability.TRANSPORT_METADATA_KEY, serverContext));
        return new McpAsyncServerExchange("session-1", new NoopSession(), McpSchema.ClientCapabilities.builder().build(),
                new McpSchema.Implementation("test-client", "1.0.0"), transportContext);
    }

    private static final class NoopSession implements McpLoggableSession {
        @Override
        public void setMinLoggingLevel(LoggingLevel minLoggingLevel) {
        }

        @Override
        public boolean isNotificationForLevelAllowed(LoggingLevel loggingLevel) {
            return false;
        }

        @Override
        public <T> Mono<T> sendRequest(String method, Object params,
                io.modelcontextprotocol.json.TypeRef<T> typeRef) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> sendNotification(String method, Object params) {
            return Mono.empty();
        }

        @Override
        public Mono<Void> closeGracefully() {
            return Mono.empty();
        }

        @Override
        public void close() {
        }
    }

    private SpanData spanByName(String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span not found: " + name));
    }
}
