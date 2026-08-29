package com.h.otheragents.observability;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.agent.observability.lifecycle.ObservationScope;
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
 * A2A 服务端 W3C 传播（设计 13.3）：每个 A2A message HTTP 请求独立提取
 * W3C Context 并创建 SERVER {@code remote_call} Span；非法 traceparent 被
 * Propagator 忽略，服务端从新根开始，不返回协议错误。
 * <p>
 * Scope 通过 {@code Mono.using} 在订阅线程上打开，覆盖阻塞 Controller 与
 * Agent 执行全程，使 A2A Agent 与模型 Observation 成为 server Span 的子节点；
 * 响应写出结束（success）、异常（fail）或取消（cancel）时恰好结束一次。
 * AgentCard 启动期 GET 查询不建业务 Trace。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class A2AObservabilityWebFilter implements WebFilter {

    private static final String AGENT_PATH_PREFIX = "/a2a/agents/";

    private final AgentObservability observability;

    public A2AObservabilityWebFilter(AgentObservability observability) {
        this.observability = observability;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!HttpMethod.POST.equals(exchange.getRequest().getMethod())
                || !exchange.getRequest().getPath().value().startsWith(AGENT_PATH_PREFIX)) {
            return chain.filter(exchange);
        }
        ObservationContext parent = observability.extract(headersOf(exchange));
        AgentObservation server = observability.span(
                ObservationSpec.of("remote_call a2a.server", HObsKind.REMOTE_CALL, "a2a-server",
                        Map.of(HAttrs.AGENT_ID, agentIdOf(exchange))),
                parent);
        return Mono.using(
                        () -> observability.scope(server.context()),
                        scope -> chain.filter(exchange),
                        ObservationScope::close)
                .doOnSuccess(ignored -> server.succeed())
                .doOnError(server::fail)
                .doOnCancel(() -> server.cancel("request cancelled"));
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

    private static String agentIdOf(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        String rest = path.substring(AGENT_PATH_PREFIX.length());
        int slash = rest.indexOf('/');
        return slash > 0 ? rest.substring(0, slash) : rest;
    }
}
