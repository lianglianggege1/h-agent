package com.h.backend.observability.mcp;

import com.h.agent.observability.AgentObservability;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * MCP 客户端动态 Header 接缝（设计 14.1）：LangChain4j
 * {@code StreamableHttpMcpTransport} 在每次构造 POST 时调用本 Supplier，此刻读取
 * 调用线程上的 current context 注入 W3C traceparent/tracestate/baggage——Header
 * 不在 McpClient 创建时固定，两次工具调用即使复用同一 McpClient 和 MCP Session
 * 也携带各自 Trace。
 * <p>
 * 基础 Header（Authorization 等）先放入，W3C Header 后放入：两者键不相交，标准
 * MCP Header（Mcp-Session-Id、Content-Type、Accept、Last-Event-ID）由 Transport
 * 自身管理，本 Supplier 从不触碰。启动期 initialize/健康检查时无活跃业务
 * Observation，注入结果为空，MCP Session 不绑定任何 Trace。
 */
public final class ObservingMcpHeadersSupplier implements dev.langchain4j.mcp.client.McpHeadersSupplier {

    private final AgentObservability observability;
    private final Supplier<Map<String, String>> baseHeaders;

    public ObservingMcpHeadersSupplier(AgentObservability observability, Supplier<Map<String, String>> baseHeaders) {
        this.observability = observability;
        this.baseHeaders = baseHeaders;
    }

    @Override
    public Map<String, String> apply(dev.langchain4j.mcp.client.McpCallContext callContext) {
        Map<String, String> headers = new LinkedHashMap<>();
        if (baseHeaders != null) {
            Map<String, String> base = baseHeaders.get();
            if (base != null) {
                headers.putAll(base);
            }
        }
        Map<String, String> w3c = new LinkedHashMap<>();
        observability.inject(observability.currentContext(), w3c);
        headers.putAll(w3c);
        return headers;
    }
}
