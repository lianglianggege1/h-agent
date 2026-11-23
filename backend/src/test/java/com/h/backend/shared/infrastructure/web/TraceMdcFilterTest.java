package com.h.backend.shared.infrastructure.web;

import com.h.agent.observability.NoopAgentObservability;
import com.h.backend.common.api.ApiResponse;
import io.opentelemetry.api.trace.Span;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TraceMdcFilterTest {

    private final TraceMdcFilter filter = new TraceMdcFilter(NoopAgentObservability.getInstance());

    @Test
    void generatesIdsAndExposesTraceIdOnResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceInChain = new AtomicReference<>();
        AtomicReference<String> spanInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            traceInChain.set(MDC.get(TraceMdcFilter.TRACE_ID_KEY));
            spanInChain.set(MDC.get(TraceMdcFilter.SPAN_ID_KEY));
            assertEquals(traceInChain.get(), ApiResponse.ok("success").traceId());
            assertEquals(traceInChain.get(), ApiResponse.error(40100, "Unauthorized").traceId());
            assertEquals(traceInChain.get(), Span.current().getSpanContext().getTraceId());
        });

        assertEquals(traceInChain.get(), response.getHeader(TraceMdcFilter.TRACE_ID_HEADER));
        assertEquals(32, traceInChain.get().length());
        assertEquals(16, spanInChain.get().length());
        assertTrue(traceInChain.get().matches("[0-9a-f]{32}"));
        assertNull(MDC.get(TraceMdcFilter.TRACE_ID_KEY));
        assertNull(MDC.get(TraceMdcFilter.SPAN_ID_KEY));
    }

    @Test
    void passesThroughValidLegacyTraceId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        request.addHeader(TraceMdcFilter.TRACE_ID_HEADER, "0af7651916cd43dd8448eb211c80319c");
        request.addHeader(TraceMdcFilter.SPAN_ID_HEADER, "b7ad6b7169203331");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> traceInChain.set(MDC.get(TraceMdcFilter.TRACE_ID_KEY)));

        assertEquals("0af7651916cd43dd8448eb211c80319c", traceInChain.get());
        assertEquals("0af7651916cd43dd8448eb211c80319c", response.getHeader(TraceMdcFilter.TRACE_ID_HEADER));
    }

    @Test
    void w3cTraceTakesPrecedenceAndCanPropagateWithExportDisabled() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/chat/agui");
        String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        request.addHeader("traceparent", "00-" + traceId + "-00f067aa0ba902b7-00");
        request.addHeader(TraceMdcFilter.TRACE_ID_HEADER, "0af7651916cd43dd8448eb211c80319c");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            var observability = NoopAgentObservability.getInstance();
            var span = observability.span(com.h.agent.observability.ObservationSpec.of("remote A2A",
                    com.h.agent.observability.HObsKind.REMOTE_CALL, "test"), observability.currentContext());
            var propagated = new java.util.HashMap<String, String>();
            observability.inject(span.context(), propagated);
            assertEquals("00-" + traceId + "-" + span.spanId() + "-00", propagated.get("traceparent"));
            assertNotEquals("00f067aa0ba902b7", span.spanId());
        });
        assertEquals(traceId, response.getHeader(TraceMdcFilter.TRACE_ID_HEADER));
    }

    @Test
    void rejectsHeaderWithLogForgeryCharacters() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        request.addHeader(TraceMdcFilter.TRACE_ID_HEADER, "bad\r\nX-Forged: 1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> traceInChain.set(MDC.get(TraceMdcFilter.TRACE_ID_KEY)));

        assertNotEquals("bad\r\nX-Forged: 1", traceInChain.get());
        assertTrue(traceInChain.get().matches("[0-9a-f]{32}"));
    }

    @Test
    void clearsMdcEvenWhenChainThrows() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();

        try {
            filter.doFilter(request, response, (req, res) -> {
                throw new ServletException("boom");
            });
        } catch (ServletException | IOException ignored) {
            // 预期异常
        }

        assertNull(MDC.get(TraceMdcFilter.TRACE_ID_KEY));
        assertNull(MDC.get(TraceMdcFilter.SPAN_ID_KEY));
    }
}
