package com.h.otheragents.observability;

/**
 * MCP 服务端传播的共享键（设计 14.2/14.3）。
 * <p>
 * {@code McpObservabilityWebFilter} 把 SERVER remote_call 的 ObservationContext 存入
 * ServerWebExchange attribute；{@code ObservingMcpTransportContextExtractor} 在
 * Transport Provider 处理请求时读取该 attribute 并放入 {@code McpTransportContext}
 * metadata——OTel Context 不存入 MCP Session，逐请求提取，Session 复用不携带
 * 上一次调用的 Trace。
 */
public final class McpObservability {

    /** ServerWebExchange attribute key：当次 POST 的 SERVER remote_call ObservationContext。 */
    public static final String EXCHANGE_ATTRIBUTE = "h-agent.mcp.observation-context";

    /** McpTransportContext metadata key：传递给工具调用的父 ObservationContext。 */
    public static final String TRANSPORT_METADATA_KEY = "h-agent.mcp.observation-context";

    private McpObservability() {
    }
}
