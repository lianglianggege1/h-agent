package com.h.otheragents.observability;

import com.h.agent.observability.lifecycle.ObservationContext;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import org.springframework.web.reactive.function.server.ServerRequest;

import java.util.Map;

/**
 * MCP 服务端传播桥（设计 14.2 Reactor bridge）：Transport Provider 处理每个
 * 请求时读取 {@code McpObservabilityWebFilter} 存入 exchange attribute 的
 * SERVER remote_call ObservationContext，经官方 {@code McpTransportContext}
 * metadata 通道带入 {@code McpAsyncServerExchange}。工具回调在 boundedElastic
 * 线程执行，经此通道而非 ThreadLocal 取得父级，线程切换不丢失因果。
 */
public final class ObservingMcpTransportContextExtractor implements McpTransportContextExtractor<ServerRequest> {

    @Override
    public McpTransportContext extract(ServerRequest request) {
        Map<String, Object> metadata = new java.util.HashMap<>();
        String user = request.headers().firstHeader("X-Order-User-Id");
        String actor = request.headers().firstHeader("X-Order-Actor");
        if (user != null) metadata.put(com.h.otheragents.order.OrderDemoMcpTools.USER_CONTEXT, user);
        if (actor != null) metadata.put(com.h.otheragents.order.OrderDemoMcpTools.ACTOR_CONTEXT, actor);
        Object context = request.exchange().getAttributes().get(McpObservability.EXCHANGE_ATTRIBUTE);
        if (context instanceof ObservationContext observationContext) {
            metadata.put(McpObservability.TRANSPORT_METADATA_KEY, observationContext);
        }
        return metadata.isEmpty() ? McpTransportContext.EMPTY : McpTransportContext.create(metadata);
    }
}
