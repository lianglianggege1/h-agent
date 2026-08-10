package com.h.backend.chat.interfaces.dto;

import java.util.List;
import java.util.Map;

/**
 * 面向前端的 Harness 事件协议。
 *
 * <p>{@code eventType/source} 保留 AgentScope 的来源事实，{@code kind/phase/target}
 * 则是前端长期依赖的产品语义。前端不需要认识 AgentScope 的全部事件类型，也不能把
 * {@code source.path} 当作协作 Agent 的授权主键。</p>
 */
public record HarnessAgentEventPayload(
        String schema,
        int schemaVersion,
        String sdkVersion,
        String runId,
        long sequence,
        String eventId,
        String eventType,
        String kind,
        String phase,
        String importance,
        String occurredAt,
        Target target,
        Source source,
        Map<String, Object> metadata,
        Map<String, Object> correlation,
        Map<String, Object> data,
        List<String> omittedFields
) {

    /**
     * 一条流内的展示目标。streamKey 可用于实时聚合；只有 subagentId 才能用于后续子对话寻址。
     */
    public record Target(
            String kind,
            String streamKey,
            String subagentId,
            String label
    ) {
    }

    /** 原始 SDK 来源，仅用于诊断和兼容，不能作为产品身份。 */
    public record Source(String scope, String path) {
    }
}
