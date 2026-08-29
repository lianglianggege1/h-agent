package com.h.otheragents.observability;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservabilityConfig;
import com.h.agent.observability.AgentObservabilityTesting;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.otheragents.mcp.McpEndpointProperties;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 服务端 WebFilter 契约测试（设计 14.2/14.3/19.5）：逐请求提取 W3C Context
 * 创建 SERVER remote_call、MCP 请求身份（Session/Header）与 Trace 身份解耦、
 * 非法 traceparent 从新根开始、GET 与未配置路径不建业务 Trace、POST 的
 * complete/error/cancel 恰好结束一次。
 */
class McpObservabilityWebFilterTest {

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
    void postContinuesClientTraceAndExposesContextToTransportExtractor() {
        observability = create();
        McpObservabilityWebFilter filter = new McpObservabilityWebFilter(observability, properties());

        AgentObservation clientCall = observability.span(
                ObservationSpec.of("remote_call mcp.tools/call", HObsKind.REMOTE_CALL, "mcp-client"),
                observability.currentContext());
        Map<String, String> w3c = new HashMap<>();
        observability.inject(clientCall.context(), w3c);

        AtomicReference<ObservationContext> contextInsideChain = new AtomicReference<>();
        MockServerWebExchange exchange = exchange(HttpMethod.POST, "/test1/mcp", w3c);
        WebFilterChain chain = ignored -> Mono.fromRunnable(
                () -> contextInsideChain.set(observability.currentContext()));
        filter.filter(exchange, chain).block();
        AgentObservabilityTesting.flush(observability);

        SpanData server = spanByName("remote_call mcp.server");
        assertEquals(clientCall.traceId(), server.getTraceId(),
                "server span must continue the client trace across the real HTTP hop");
        assertEquals(clientCall.spanId(), server.getParentSpanId(),
                "server span must be a child of the client remote_call span");
        assertEquals("mcp-server", server.getAttributes().get(AttributeKey.stringKey(HAttrs.RUNTIME)));
        assertEquals("success", server.getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));

        assertNotNull(contextInsideChain.get());
        assertEquals(server.getSpanId(), Span.fromContext(contextInsideChain.get().otelContext())
                        .getSpanContext().getSpanId(),
                "code inside the filter chain observes the server span as current");
        ObservationContext attribute = (ObservationContext) exchange.getAttributes()
                .get(McpObservability.EXCHANGE_ATTRIBUTE);
        assertNotNull(attribute, "server observation context must be exposed via exchange attribute");
        assertEquals(server.getSpanId(), Span.fromContext(attribute.otelContext())
                .getSpanContext().getSpanId());
    }

    @Test
    void extractorCarriesServerContextIntoTransportContext() {
        observability = create();
        AgentObservation server = observability.span(
                ObservationSpec.of("remote_call mcp.server", HObsKind.REMOTE_CALL, "mcp-server"),
                observability.currentContext());
        MockServerWebExchange exchange = exchange(HttpMethod.POST, "/test1/mcp", Map.of());
        exchange.getAttributes().put(McpObservability.EXCHANGE_ATTRIBUTE, server.context());
        org.springframework.web.reactive.function.server.ServerRequest request =
                org.springframework.web.reactive.function.server.ServerRequest.create(exchange, java.util.List.of());

        io.modelcontextprotocol.common.McpTransportContext transport =
                new ObservingMcpTransportContextExtractor().extract(request);

        assertEquals(server.context(), transport.get(McpObservability.TRANSPORT_METADATA_KEY),
                "extractor must carry the per-request server observation context into the official channel");

        MockServerWebExchange untraced = exchange(HttpMethod.POST, "/test1/mcp", Map.of());
        org.springframework.web.reactive.function.server.ServerRequest untracedRequest =
                org.springframework.web.reactive.function.server.ServerRequest.create(untraced, java.util.List.of());
        assertNull(new ObservingMcpTransportContextExtractor().extract(untracedRequest)
                        .get(McpObservability.TRANSPORT_METADATA_KEY),
                "a request without the filter attribute must produce empty transport context");
    }

    @Test
    void invalidTraceparentStartsFreshRootWithoutProtocolError() {
        observability = create();
        McpObservabilityWebFilter filter = new McpObservabilityWebFilter(observability, properties());

        String bogusTraceparent = "00-00000000000000000000000000000000-0000000000000000-00";
        AtomicReference<ObservationContext> contextInsideChain = new AtomicReference<>();
        MockServerWebExchange exchange = exchange(HttpMethod.POST, "/test1/mcp",
                Map.of("traceparent", bogusTraceparent));
        WebFilterChain chain = ignored -> Mono.fromRunnable(
                () -> contextInsideChain.set(observability.currentContext()));
        filter.filter(exchange, chain).block();
        AgentObservabilityTesting.flush(observability);

        SpanData server = spanByName("remote_call mcp.server");
        assertNotEquals("00000000000000000000000000000000", server.getTraceId(),
                "an all-zero (invalid) traceparent must be ignored by the propagator");
        assertTrue(server.getTraceId().chars().allMatch(c -> "0123456789abcdef".indexOf(c) >= 0),
                "server starts a fresh valid root trace");
        assertEquals("success", server.getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));
    }

    @Test
    void getAndUnknownPathsAreNotTraced() {
        observability = create();
        McpObservabilityWebFilter filter = new McpObservabilityWebFilter(observability, properties());

        filter.filter(exchange(HttpMethod.GET, "/test1/mcp", Map.of()), chain()).block();
        filter.filter(exchange(HttpMethod.DELETE, "/test1/mcp", Map.of()), chain()).block();
        filter.filter(exchange(HttpMethod.POST, "/a2a/agents/creative-writer", Map.of()), chain()).block();
        filter.filter(exchange(HttpMethod.POST, "/actuator/health", Map.of()), chain()).block();
        AgentObservabilityTesting.flush(observability);

        assertTrue(exporter.getFinishedSpanItems().isEmpty(),
                "subsidiary GET/SSE channel, DELETE and non-MCP paths must not create business traces");
    }

    @Test
    void errorFailsServerSpanAndCancelCancelsIt() {
        observability = create();
        McpObservabilityWebFilter filter = new McpObservabilityWebFilter(observability, properties());

        filter.filter(exchange(HttpMethod.POST, "/test2/mcp", Map.of()), ignored -> Mono.error(
                        new IllegalStateException("stream broken")))
                .onErrorComplete().block();
        AgentObservabilityTesting.flush(observability);
        assertEquals("failure", spanByName("remote_call mcp.server")
                .getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));

        exporter.reset();
        filter.filter(exchange(HttpMethod.POST, "/test2/mcp", Map.of()), ignored -> Mono.never())
                .subscribe().dispose();
        AgentObservabilityTesting.flush(observability);
        assertEquals("cancelled", spanByName("remote_call mcp.server")
                .getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));
    }

    private static WebFilterChain chain() {
        return ignored -> Mono.empty();
    }

    private static McpEndpointProperties properties() {
        McpEndpointProperties properties = new McpEndpointProperties();
        McpEndpointProperties.Endpoint first = new McpEndpointProperties.Endpoint();
        first.setPath("/test1/mcp");
        first.setToken("dev-token-test1");
        McpEndpointProperties.Endpoint second = new McpEndpointProperties.Endpoint();
        second.setPath("/test2/mcp");
        second.setToken("dev-token-test2");
        properties.getEndpoints().put("test1", first);
        properties.getEndpoints().put("test2", second);
        return properties;
    }

    private static MockServerWebExchange exchange(HttpMethod method, String path, Map<String, String> headers) {
        HttpHeaders httpHeaders = new HttpHeaders();
        headers.forEach(httpHeaders::add);
        MockServerHttpRequest request = MockServerHttpRequest.method(method, path)
                .headers(httpHeaders)
                .body("{}");
        return MockServerWebExchange.from(request);
    }

    private SpanData spanByName(String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span not found: " + name));
    }
}
