package com.h.agent.observability;

import io.opentelemetry.sdk.trace.export.SpanExporter;

/**
 * 下游模块（backend / other-agents）契约测试的公共入口：
 * 用自定义 {@link SpanExporter}（通常是 in-memory）构建真实实现并强制 flush。
 * 生产代码不得使用本类。
 */
public final class AgentObservabilityTesting {

    private AgentObservabilityTesting() {
    }

    public static AgentObservability build(AgentObservabilityConfig config, SpanExporter exporter) {
        return DefaultAgentObservability.buildWithExporter(config, exporter);
    }

    public static void flush(AgentObservability observability) {
        if (observability instanceof DefaultAgentObservability impl) {
            impl.flushForTest();
        }
    }
}
