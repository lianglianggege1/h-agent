package com.h.otheragents.observability;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservabilityConfig;
import com.h.agent.observability.AgentObservabilityTesting;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.lifecycle.ObservationContext;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2A 服务端 W3C 传播契约测试（设计 13.3）：SERVER remote_call 延续客户端 Trace、
 * chain 内代码（Agent/模型 Observation）挂到 server Span 下、非法 traceparent
 * 降级新根、AgentCard GET 不建业务 Trace、生命周期恰好结束一次。
 */
class A2AObservabilityWebFilterTest {

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
    void serverSpanContinuesClientTraceAndParentsChainObservations() {
        observability = create();
        AgentObservation clientCall = observability.span(
                ObservationSpec.of("remote_call a2a.message/send", HObsKind.REMOTE_CALL, "a2a-client"),
                observability.currentContext());
        Map<String, String> w3cHeaders = new HashMap<>();
        observability.inject(clientCall.context(), w3cHeaders);
        clientCall.succeed();

        A2AObservabilityWebFilter filter = new A2AObservabilityWebFilter(observability);
        HttpHeaders httpHeaders = new HttpHeaders();
        w3cHeaders.forEach(httpHeaders::add);
        MockServerHttpRequest request = MockServerHttpRequest
                .post("http://localhost:8082/a2a/agents/creative-writer")
                .headers(httpHeaders)
                .body("{\"jsonrpc\":\"2.0\"}");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<ObservationContext> contextInsideChain = new AtomicReference<>();
        AtomicReference<AgentObservation> agentInsideChain = new AtomicReference<>();
        WebFilterChain chain = ignored -> Mono.fromRunnable(() -> {
            contextInsideChain.set(observability.currentContext());
            agentInsideChain.set(observability.span(
                    ObservationSpec.of("agent creative-writer", HObsKind.AGENT, "langchain4j"),
                    observability.currentContext()));
        });
        filter.filter(exchange, chain).block();
        agentInsideChain.get().succeed();
        AgentObservabilityTesting.flush(observability);

        SpanData server = spanByName("remote_call a2a.server");
        assertEquals(clientCall.traceId(), server.getTraceId(),
                "server span must continue the client's trace");
        assertEquals(clientCall.spanId(), server.getParentSpanId(),
                "server span must be a child of the client remote_call");
        assertEquals("creative-writer", server.getAttributes().get(AttributeKey.stringKey(HAttrs.AGENT_ID)));
        assertEquals("a2a-server", server.getAttributes().get(AttributeKey.stringKey(HAttrs.RUNTIME)));
        assertEquals("success", server.getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));

        assertNotNull(contextInsideChain.get());
        assertEquals(server.getSpanId(), io.opentelemetry.api.trace.Span
                        .fromContext(contextInsideChain.get().otelContext()).getSpanContext().getSpanId(),
                "code running inside the filter chain must observe the server span as current");

        SpanData agent = spanByName("agent creative-writer");
        assertEquals(server.getTraceId(), agent.getTraceId());
        assertEquals(server.getSpanId(), agent.getParentSpanId(),
                "server-side agent observation must nest under the server remote_call");
    }

    @Test
    void invalidTraceparentStartsFreshRoot() {
        observability = create();
        A2AObservabilityWebFilter filter = new A2AObservabilityWebFilter(observability);
        MockServerHttpRequest request = MockServerHttpRequest
                .post("http://localhost:8082/a2a/agents/style-editor")
                .header("traceparent", "this-is-not-a-valid-traceparent")
                .body("{}");
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        filter.filter(exchange, chain -> Mono.empty()).block();
        AgentObservabilityTesting.flush(observability);

        SpanData server = spanByName("remote_call a2a.server");
        assertTrue(server.getTraceId().matches("[0-9a-f]{32}"),
                "server must start a fresh root trace when traceparent is invalid");
        assertTrue(server.getParentSpanId().isBlank() || "0000000000000000".equals(server.getParentSpanId()),
                "invalid traceparent must not create a parent");
    }

    @Test
    void missingTraceparentAlsoStartsFreshRoot() {
        observability = create();
        A2AObservabilityWebFilter filter = new A2AObservabilityWebFilter(observability);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://localhost:8082/a2a/agents/audience-editor").body("{}"));

        filter.filter(exchange, chain -> Mono.empty()).block();
        AgentObservabilityTesting.flush(observability);

        assertEquals("success", spanByName("remote_call a2a.server")
                .getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));
        assertTrue(spanByName("remote_call a2a.server").getParentSpanId().isBlank()
                || "0000000000000000".equals(spanByName("remote_call a2a.server").getParentSpanId()));
    }

    @Test
    void agentCardGetIsNotTraced() {
        observability = create();
        A2AObservabilityWebFilter filter = new A2AObservabilityWebFilter(observability);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("http://localhost:8082/a2a/agents/creative-writer/.well-known/agent-card.json"));

        filter.filter(exchange, chain -> Mono.empty()).block();
        AgentObservabilityTesting.flush(observability);

        assertTrue(exporter.getFinishedSpanItems().isEmpty(),
                "AgentCard startup queries must not create business traces");
    }

    @Test
    void nonA2aRequestsAreNotTraced() {
        observability = create();
        A2AObservabilityWebFilter filter = new A2AObservabilityWebFilter(observability);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://localhost:8082/api/other").body("{}"));

        filter.filter(exchange, chain -> Mono.empty()).block();
        AgentObservabilityTesting.flush(observability);

        assertTrue(exporter.getFinishedSpanItems().isEmpty());
    }

    @Test
    void chainErrorFailsServerSpan() {
        observability = create();
        A2AObservabilityWebFilter filter = new A2AObservabilityWebFilter(observability);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.post("http://localhost:8082/a2a/agents/creative-writer").body("{}"));
        IllegalStateException failure = new IllegalStateException("agent exploded");

        assertThrows(IllegalStateException.class,
                () -> filter.filter(exchange, chain -> Mono.error(failure)).block());
        AgentObservabilityTesting.flush(observability);

        SpanData server = spanByName("remote_call a2a.server");
        assertEquals("failure", server.getAttributes().get(AttributeKey.stringKey(HAttrs.OUTCOME)));
        assertEquals("java.lang.IllegalStateException",
                server.getAttributes().get(AttributeKey.stringKey("exception.type")));
    }

    private SpanData spanByName(String name) {
        return exporter.getFinishedSpanItems().stream()
                .filter(span -> name.equals(span.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("span not found: " + name));
    }
}
