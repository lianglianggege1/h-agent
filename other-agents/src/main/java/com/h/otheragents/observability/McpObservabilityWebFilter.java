package com.h.otheragents.observability;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.agent.observability.lifecycle.ObservationScope;
import com.h.otheragents.mcp.McpEndpointProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * MCP 服务端 W3C 传播（设计 14.2）：每个 MCP JSON-RPC POST 独立提取 W3C Context
 * 并创建 SERVER {@code remote_call} Span；非法 traceparent 被 Propagator 忽略，
 * 服务端从新根开始，不返回协议错误。
 * <p>
 * SERVER Span 的 ObservationContext 同时存入 exchange attribute，由
 * {@code ObservingMcpTransportContextExtractor} 经官方 {@code McpTransportContext}
 * 通道传递给工具调用——工具回调运行在 boundedElastic 线程，Reactor thread hop 后
 * 仍能取得正确父级，不依赖 ThreadLocal。POST 的 SSE 响应写出结束（success）、
 * 异常（fail）或取消（cancel）时恰好结束一次；共享 subsidiary GET/SSE 通道与
 * DELETE 不建业务 Trace。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class McpObservabilityWebFilter implements WebFilter {

    private final AgentObservability observability;
    private final McpEndpointProperties mcpProperties;

    public McpObservabilityWebFilter(AgentObservability observability, McpEndpointProperties mcpProperties) {
        this.observability = observability;
        this.mcpProperties = mcpProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!HttpMethod.POST.equals(exchange.getRequest().getMethod())
                || !isConfiguredMcpPath(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        ObservationContext parent = observability.extract(headersOf(exchange));
        AgentObservation server = observability.span(
                ObservationSpec.of("remote_call mcp.server", HObsKind.REMOTE_CALL, "mcp-server",
                        Map.of(HAttrs.AGENT_ID, exchange.getRequest().getPath().value())),
                parent);
        exchange.getAttributes().put(McpObservability.EXCHANGE_ATTRIBUTE, server.context());
        return Mono.using(
                        () -> observability.scope(server.context()),
                        scope -> chain.filter(exchange),
                        ObservationScope::close)
                .doOnSuccess(ignored -> server.succeed())
                .doOnError(server::fail)
                .doOnCancel(() -> server.cancel("request cancelled"));
    }

    private boolean isConfiguredMcpPath(String path) {
        for (McpEndpointProperties.Endpoint endpoint : mcpProperties.getEndpoints().values()) {
            if (path.equals(endpoint.getPath())) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, String> headersOf(ServerWebExchange exchange) {
        Map<String, String> headers = new HashMap<>();
        exchange.getRequest().getHeaders().forEach((name, values) -> {
            if (!values.isEmpty()) {
                headers.put(name, values.get(0));
            }
        });
        return headers;
    }
}
