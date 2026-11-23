package com.h.backend.shared.infrastructure.web;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.lifecycle.ExecutionContextSnapshot;
import com.h.capabilities.shared.infrastructure.web.ExecutionLogContext;
import io.opentelemetry.api.trace.SpanId;
import io.opentelemetry.api.trace.TraceId;
import io.opentelemetry.sdk.trace.IdGenerator;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceMdcFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String SPAN_ID_HEADER = "X-Span-Id";
    public static final String TRACE_ID_KEY = ExecutionLogContext.TRACE_ID_KEY;
    public static final String SPAN_ID_KEY = ExecutionLogContext.SPAN_ID_KEY;

    private final AgentObservability observability;

    public TraceMdcFilter(AgentObservability observability) {
        this.observability = observability;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var headers = new HashMap<String, String>();
        for (String name : new String[]{"traceparent", "tracestate"}) {
            String value = request.getHeader(name);
            if (value != null) headers.put(name, value);
        }
        var parent = observability.extract(headers);
        if (!headers.containsKey("traceparent")) {
            String traceId = request.getHeader(TRACE_ID_HEADER);
            if (traceId != null && TraceId.isValid(traceId)) {
                String spanId = request.getHeader(SPAN_ID_HEADER);
                if (spanId == null || !SpanId.isValid(spanId)) spanId = IdGenerator.random().generateSpanId();
                parent = observability.extract(Map.of("traceparent", "00-" + traceId + "-" + spanId + "-01"));
            }
        }

        var server = observability.serverSpan(ObservationSpec.of("http " + request.getMethod(), HObsKind.REMOTE_CALL,
                "http-server"), parent);
        response.setHeader(TRACE_ID_HEADER, server.traceId());
        try (var scope = ExecutionContextSnapshot.forObservation(server.context()).scope()) {
            try {
                chain.doFilter(request, response);
                server.attribute("http.response.status_code", response.getStatus());
                server.succeed();
            } catch (ServletException | IOException | RuntimeException failure) {
                server.fail(failure);
                throw failure;
            }
        }
    }
}
