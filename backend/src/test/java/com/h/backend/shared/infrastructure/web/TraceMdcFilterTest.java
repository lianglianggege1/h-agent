package com.h.backend.shared.infrastructure.web;

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

    private final TraceMdcFilter filter = new TraceMdcFilter();

    @Test
    void generatesIdsAndExposesTraceIdOnResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceInChain = new AtomicReference<>();
        AtomicReference<String> spanInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            traceInChain.set(MDC.get(TraceMdcFilter.TRACE_ID_KEY));
            spanInChain.set(MDC.get(TraceMdcFilter.SPAN_ID_KEY));
        });

        assertEquals(traceInChain.get(), response.getHeader(TraceMdcFilter.TRACE_ID_HEADER));
        assertEquals(32, traceInChain.get().length());
        assertEquals(16, spanInChain.get().length());
        assertTrue(traceInChain.get().matches("[0-9a-f]{32}"));
        assertNull(MDC.get(TraceMdcFilter.TRACE_ID_KEY));
        assertNull(MDC.get(TraceMdcFilter.SPAN_ID_KEY));
    }

    @Test
    void passesThroughSafeIncomingHeader() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/chat");
        request.addHeader(TraceMdcFilter.TRACE_ID_HEADER, "trace-from-gateway-123");
        request.addHeader(TraceMdcFilter.SPAN_ID_HEADER, "span-upstream-1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> traceInChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> traceInChain.set(MDC.get(TraceMdcFilter.TRACE_ID_KEY)));

        assertEquals("trace-from-gateway-123", traceInChain.get());
        assertEquals("trace-from-gateway-123", response.getHeader(TraceMdcFilter.TRACE_ID_HEADER));
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
