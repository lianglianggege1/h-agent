package com.h.otheragents.observability;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.NoopAgentObservability;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.lifecycle.ExecutionContextSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class A2AObservabilityWebFilter implements WebFilter {

    public static final String EXCHANGE_ATTRIBUTE = "h-agent.a2a.observation-context";
    static final String TRACE_HEADER = "X-Trace-Id";

    private static final Logger log = LoggerFactory.getLogger(A2AObservabilityWebFilter.class);
    private static final String AGENT_PATH_PREFIX = "/a2a/agents/";

    private final AgentObservability observability;

    public A2AObservabilityWebFilter(AgentObservability observability) {
        this.observability = observability;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // MCP POST requests have their own server observation.
        if (exchange.getResponse().getHeaders().containsHeader(TRACE_HEADER)) return chain.filter(exchange);

        String path = exchange.getRequest().getPath().value();
        boolean a2a = HttpMethod.POST.equals(exchange.getRequest().getMethod()) && path.startsWith(AGENT_PATH_PREFIX);
        String agentId = a2a ? path.substring(AGENT_PATH_PREFIX.length()).split("/", 2)[0] : null;
        // Card discovery and other routes need log correlation without creating an Agent trace.
        var tracer = a2a ? observability : NoopAgentObservability.getInstance();
        var parent = tracer.extract(headersOf(exchange));
        var server = tracer.serverSpan(a2a
                ? ObservationSpec.of("remote_call a2a.server", HObsKind.REMOTE_CALL, "a2a-server",
                        Map.of(HAttrs.AGENT_ID, agentId))
                : ObservationSpec.of("http " + exchange.getRequest().getMethod(), HObsKind.REMOTE_CALL, "http-server"),
                parent);
        exchange.getResponse().getHeaders().set(TRACE_HEADER, server.traceId());
        exchange.getAttributes().put(EXCHANGE_ATTRIBUTE, server.context());
        var snapshot = ExecutionContextSnapshot.forObservation(server.context());
        return Mono.defer(() -> chain.filter(exchange))
                .doOnSuccess(ignored -> {
                    var status = exchange.getResponse().getStatusCode();
                    server.attribute("http.response.status_code", status == null ? HttpStatus.OK.value() : status.value());
                    server.succeed();
                })
                .doOnError(server::fail)
                .doOnCancel(() -> server.cancel("request cancelled"))
                .doFinally(signal -> {
                    if (!a2a) return;
                    try (var scope = snapshot.scope()) {
                        var status = exchange.getResponse().getStatusCode();
                        log.info("A2A request finished agent={} status={} outcome={}", agentId,
                                status == null && signal == reactor.core.publisher.SignalType.ON_COMPLETE
                                        ? HttpStatus.OK : status, signal);
                    }
                });
    }

    static Map<String, String> headersOf(ServerWebExchange exchange) {
        Map<String, String> headers = new HashMap<>();
        for (String name : new String[]{"traceparent", "tracestate", "baggage"}) {
            String value = exchange.getRequest().getHeaders().getFirst(name);
            if (value != null) headers.put(name, value);
        }
        return headers;
    }
}
