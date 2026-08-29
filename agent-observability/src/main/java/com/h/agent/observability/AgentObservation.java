package com.h.agent.observability;

import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.agent.observability.semantic.SemanticContent;

public interface AgentObservation extends AutoCloseable {

    String traceId();

    String spanId();

    ObservationContext context();

    void attribute(String key, String value);

    /**
     * Langfuse 只把整数类型的 {@code gen_ai.usage.*} 属性映射到 observation usage，
     * 字符串值会被静默忽略（token 计数显示为 0），usage 类属性必须走此重载。
     */
    void attribute(String key, long value);

    /**
     * 记录一次模型调用的 token 用量（null 表示未知）。Langfuse 4 的 OTLP ingestion
     * 对自定义 instrumentation scope 只认 {@code langfuse.observation.usage_details}
     * JSON 字符串属性（{@code gen_ai.usage.*} 仅对 langfuse-sdk / ai 等 scope 生效），
     * 因此除语义约定属性外必须同时写入该 JSON。
     */
    void usage(Number inputTokens, Number outputTokens, Number totalTokens);

    void input(SemanticContent content);

    void output(SemanticContent content);

    void succeed();

    void fail(Throwable error);

    void cancel(String reason);

    @Override
    void close();
}
