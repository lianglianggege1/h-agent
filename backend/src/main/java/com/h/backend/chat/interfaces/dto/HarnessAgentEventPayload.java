package com.h.backend.chat.interfaces.dto;

import java.util.List;
import java.util.Map;

/**
 * 面向前端的 Harness 事件协议。
 *
 * <p>{@code eventType/source} 保留 AgentScope 的来源事实，{@code kind/phase}
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
        Source source,
        Map<String, Object> metadata,
        Map<String, Object> correlation,
        Map<String, Object> data,
        Projection projection,
        List<String> omittedFields
) {

    /** 原始 SDK 来源，仅用于诊断和兼容，不能作为产品身份。 */
    public record Source(String scope, String path) {
    }

    /** 当前事件事务已提交的最新协作者状态；不包含版本号或事件日志副本。 */
    public record Projection(HarnessSubagentSummaryDto subagent) {
    }
}
