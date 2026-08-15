package com.h.backend.chat.domain.agent;

import com.fasterxml.jackson.core.type.TypeReference;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.chat.interfaces.dto.HarnessAgentEventPayload;
import com.h.backend.chat.interfaces.dto.HarnessSubagentSummaryDto;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentEventType;
import io.agentscope.core.message.Msg;
import io.agentscope.core.util.JsonUtils;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 把 AgentScope 事件规范化为前端可长期依赖的、对用户有意义的 Harness 事件。
 *
 * <p>这是 SDK 事件模型与产品事件模型之间的主 seam。每一条 AgentEvent 都会产生一条
 * {@code harness_event}；对于工具参数、二进制数据等不适合直接进入浏览器的字段，只裁剪字段
 * 并通过 {@code omittedFields} 明示，而不丢弃整条事件。SDK 升级造成的差异应收敛在这里，
 * 而不是扩散到前端。</p>
 */
@Component
public class HarnessEventMapper {

    private static final String SCHEMA = "harness.agent-event";
    private static final int SCHEMA_VERSION = 3;
    private static final String SDK_VERSION = "2.0.1";
    private static final Set<String> SAFE_METADATA_KEYS = Set.of(
            "taskId", "parentSessionId", "notificationType"
    );

    /** 将一条原始事件映射为单条、时间有序的产品事件。 */
    public ChatStreamEvent map(long runId, long sequence, AgentEvent event) {
        return map(runId, sequence, event, null, null);
    }

    /**
     * 映射事件并补充产品层已解析出的直接父 Session。
     * AgentScope 2.0.1 exposure 本身不携带父 Session，这个事实由执行流的 source 映射提供。
     */
    public ChatStreamEvent map(
            long runId,
            long sequence,
            AgentEvent event,
            String exposedParentSessionId
    ) {
        return map(runId, sequence, event, exposedParentSessionId, null);
    }

    public ChatStreamEvent map(
            long runId,
            long sequence,
            AgentEvent event,
            String exposedParentSessionId,
            HarnessSubagentSummaryDto projectedSubagent
    ) {
        return map(runId, sequence, event, exposedParentSessionId, null, projectedSubagent);
    }

    public ChatStreamEvent map(
            long runId,
            long sequence,
            AgentEvent event,
            String exposedParentSessionId,
            String eventAgentSessionId,
            HarnessSubagentSummaryDto projectedSubagent
    ) {
        return mapInternal(
                String.valueOf(runId), sequence, event, exposedParentSessionId,
                eventAgentSessionId, projectedSubagent, null
        );
    }

    /**
     * 把可重连的子会话观察流映射成与父请求 SSE 相同的事件协议。
     * streamId/sequence 来自子会话事件通道，复用既有 runId + sequence + eventId 游标，
     * 不再引入第二套 eventSeq。
     */
    public ChatStreamEvent mapObservedSubagent(
            String streamId,
            long sequence,
            AgentEvent event,
            String agentSessionId
    ) {
        return mapInternal(
                streamId,
                sequence,
                event,
                null,
                agentSessionId,
                null,
                "product-relay/" + agentSessionId
        );
    }

    private ChatStreamEvent mapInternal(
            String runId,
            long sequence,
            AgentEvent event,
            String exposedParentSessionId,
            String eventAgentSessionId,
            HarnessSubagentSummaryDto projectedSubagent,
            String sourceOverride
    ) {
        EventSemantics semantics = semanticsOf(event.getType());
        Map<String, Object> serialized = serialize(event);
        Map<String, Object> correlation = extractCorrelation(serialized);
        Map<String, Object> data = normalizeData(event, serialized);
        if (eventAgentSessionId != null && !eventAgentSessionId.isBlank()) {
            // source.path 只描述 SDK 的 Agent 路径；它既不是产品授权键，也无法区分同类型
            // Agent 的并行调用。把执行器已解析出的产品 Session 显式带给前端用于增量路由。
            data.put("agentSessionId", eventAgentSessionId);
        }
        if (event.getType() == AgentEventType.SUBAGENT_EXPOSED
                && exposedParentSessionId != null
                && !exposedParentSessionId.isBlank()) {
            data.put("parentSessionId", exposedParentSessionId);
        }
        String sourcePath = sourceOverride == null ? event.getSource() : sourceOverride;
        HarnessAgentEventPayload payload = new HarnessAgentEventPayload(
                SCHEMA,
                SCHEMA_VERSION,
                SDK_VERSION,
                runId,
                sequence,
                event.getId(),
                event.getType().name(),
                semantics.kind(),
                semantics.phase(),
                semantics.importance(),
                event.getCreatedAt(),
                new HarnessAgentEventPayload.Source(scopeOf(sourcePath), sourcePath),
                safeMetadata(event.getMetadata()),
                Collections.unmodifiableMap(correlation),
                Collections.unmodifiableMap(data),
                projectedSubagent == null
                        ? null
                        : new HarnessAgentEventPayload.Projection(projectedSubagent),
                omittedFields(event.getType())
        );
        return new ChatStreamEvent("harness_event", "", null, payload);
    }

    private Map<String, Object> serialize(AgentEvent event) {
        return new LinkedHashMap<>(JsonUtils.getJsonCodec().convertValue(
                event,
                new TypeReference<Map<String, Object>>() {
                }
        ));
    }

    private Map<String, Object> extractCorrelation(Map<String, Object> serialized) {
        Map<String, Object> correlation = new LinkedHashMap<>();
        moveIfPresent(serialized, correlation, "replyId");
        moveIfPresent(serialized, correlation, "blockId");
        moveIfPresent(serialized, correlation, "toolCallId");
        return correlation;
    }

    /**
     * 只保留当前产品事件真正需要的字段。尤其不把 tool-call JSON 增量、二进制 data block
     * 或完整 Msg metadata 原样暴露给浏览器。
     */
    private Map<String, Object> normalizeData(AgentEvent event, Map<String, Object> serialized) {
        Map<String, Object> data = new LinkedHashMap<>();
        switch (event.getType()) {
            case AGENT_START -> copy(serialized, data, "sessionId", "name", "role");
            case AGENT_RESULT -> addResult(data, (AgentResultEvent) event);
            case MODEL_CALL_END -> copy(serialized, data, "usage");
            case TEXT_BLOCK_DELTA, THINKING_BLOCK_DELTA -> copy(serialized, data, "delta");
            case TOOL_CALL_START, TOOL_CALL_DELTA, TOOL_CALL_END, TOOL_RESULT_START,
                 TOOL_RESULT_TEXT_DELTA, TOOL_RESULT_DATA_DELTA ->
                    copy(serialized, data, "toolCallName");
            case TOOL_RESULT_END -> copy(serialized, data, "toolCallName", "state");
            case EXCEED_MAX_ITERS -> copy(serialized, data, "maxIters", "currentIter");
            case REQUIRE_USER_CONFIRM, REQUIRE_EXTERNAL_EXECUTION ->
                    summarizeItems(serialized, data, "toolCalls", "id", "name", "toolCallId", "toolCallName");
            case USER_CONFIRM_RESULT -> summarizeConfirmResults(serialized, data);
            case EXTERNAL_EXECUTION_RESULT ->
                    summarizeItems(serialized, data, "toolResults", "id", "name", "toolCallId", "toolCallName");
            case REQUEST_STOP -> copy(serialized, data, "reason", "generateReason");
            case SUBAGENT_EXPOSED -> copy(
                    serialized,
                    data,
                    "agentId",
                    "sessionId",
                    "label"
            );
            case ALL_TOOLS_DENIED -> summarizeItems(
                    serialized,
                    data,
                    "deniedToolCalls",
                    "id",
                    "name",
                    "toolCallId",
                    "toolCallName"
            );
            case HINT_BLOCK -> copy(serialized, data, "hintSource");
            case CUSTOM -> copy(serialized, data, "name", "value");
            default -> {
                // START/END 类事件本身即表达状态，不需要额外 data。
            }
        }
        return data;
    }

    private void addResult(Map<String, Object> data, AgentResultEvent event) {
        Msg result = event.getResult();
        if (result == null) {
            return;
        }
        data.put("messageId", result.getId());
        data.put("content", result.getTextContent());
        if (result.getGenerateReason() != null) {
            data.put("generateReason", result.getGenerateReason().name());
        }
    }

    private EventSemantics semanticsOf(AgentEventType eventType) {
        return switch (eventType) {
            case AGENT_START -> secondary("AGENT_STATUS", "START");
            case AGENT_END -> secondary("AGENT_STATUS", "END");
            case AGENT_RESULT -> primary("MODEL_OUTPUT", "RESULT");
            case MODEL_CALL_START -> secondary("MODEL_STATUS", "START");
            case MODEL_CALL_END -> secondary("MODEL_STATUS", "END");
            case TEXT_BLOCK_START -> primary("MODEL_OUTPUT", "START");
            case TEXT_BLOCK_DELTA -> primary("MODEL_OUTPUT", "DELTA");
            case TEXT_BLOCK_END -> primary("MODEL_OUTPUT", "END");
            case THINKING_BLOCK_START -> secondary("THINKING", "START");
            case THINKING_BLOCK_DELTA -> secondary("THINKING", "DELTA");
            case THINKING_BLOCK_END -> secondary("THINKING", "END");
            case DATA_BLOCK_START -> secondary("DATA", "START");
            case DATA_BLOCK_DELTA -> secondary("DATA", "DELTA");
            case DATA_BLOCK_END -> secondary("DATA", "END");
            case TOOL_CALL_START -> secondary("ACTION", "START");
            case TOOL_CALL_DELTA -> secondary("ACTION", "DELTA");
            case TOOL_CALL_END -> secondary("ACTION", "END");
            case TOOL_RESULT_START -> secondary("ACTION_RESULT", "START");
            case TOOL_RESULT_TEXT_DELTA, TOOL_RESULT_DATA_DELTA -> secondary("ACTION_RESULT", "DELTA");
            case TOOL_RESULT_END -> secondary("ACTION_RESULT", "END");
            case REQUIRE_USER_CONFIRM, REQUIRE_EXTERNAL_EXECUTION -> primary("ACTION_REQUIRED", "EVENT");
            case USER_CONFIRM_RESULT, EXTERNAL_EXECUTION_RESULT -> primary("NOTIFICATION", "EVENT");
            case REQUEST_STOP -> primary("NOTIFICATION", "EVENT");
            case SUBAGENT_EXPOSED -> primary("SUBAGENT", "EVENT");
            case EXCEED_MAX_ITERS, ALL_TOOLS_DENIED -> primary("ERROR", "EVENT");
            case HINT_BLOCK -> secondary("HINT", "EVENT");
            case CUSTOM -> primary("NOTIFICATION", "EVENT");
        };
    }

    /**
     * 字段裁剪不改变 eventType/kind/phase。前端可据此保留时间线位置，而无需猜测事件是否丢失。
     */
    private List<String> omittedFields(AgentEventType eventType) {
        return switch (eventType) {
            case DATA_BLOCK_DELTA -> List.of("data.delta");
            case TOOL_CALL_DELTA -> List.of("data.delta");
            case TOOL_RESULT_TEXT_DELTA -> List.of("data.delta");
            case TOOL_RESULT_DATA_DELTA -> List.of("data.data");
            case HINT_BLOCK -> List.of("data.hint");
            case REQUIRE_USER_CONFIRM, REQUIRE_EXTERNAL_EXECUTION -> List.of("data.toolCalls[*].input");
            case USER_CONFIRM_RESULT -> List.of("data.confirmResults[*].toolCall", "data.confirmResults[*].rules");
            case EXTERNAL_EXECUTION_RESULT -> List.of("data.toolResults[*].content");
            case ALL_TOOLS_DENIED -> List.of("data.deniedToolCalls[*].input");
            default -> List.of();
        };
    }

    private EventSemantics primary(String kind, String phase) {
        return new EventSemantics(kind, phase, "PRIMARY");
    }

    private EventSemantics secondary(String kind, String phase) {
        return new EventSemantics(kind, phase, "SECONDARY");
    }

    private String scopeOf(String sourcePath) {
        return sourcePath == null || sourcePath.isBlank() ? "PARENT" : "SUBAGENT";
    }

    private Map<String, Object> safeMetadata(Map<String, Object> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> safe = new LinkedHashMap<>();
        for (String key : SAFE_METADATA_KEYS) {
            if (metadata.containsKey(key)) {
                safe.put(key, metadata.get(key));
            }
        }
        return Collections.unmodifiableMap(safe);
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            if (source.containsKey(key)) {
                target.put(key, source.get(key));
            }
        }
    }

    /** 工具参数可能包含密钥或大块输入；浏览器只需要稳定身份和展示名称。 */
    private void summarizeItems(
            Map<String, Object> source,
            Map<String, Object> target,
            String sourceKey,
            String... safeKeys
    ) {
        Object rawItems = source.get(sourceKey);
        if (!(rawItems instanceof List<?> items)) {
            return;
        }
        List<Map<String, Object>> summaries = items.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .map(item -> {
                    Map<String, Object> summary = new LinkedHashMap<>();
                    for (String key : safeKeys) {
                        if (item.containsKey(key)) {
                            summary.put(key, item.get(key));
                        }
                    }
                    return summary;
                })
                .toList();
        target.put(sourceKey, summaries);
        target.put("itemCount", summaries.size());
    }

    private void summarizeConfirmResults(Map<String, Object> source, Map<String, Object> target) {
        Object rawResults = source.get("confirmResults");
        if (!(rawResults instanceof List<?> results)) {
            return;
        }
        long confirmedCount = results.stream()
                .filter(Map.class::isInstance)
                .map(Map.class::cast)
                .filter(result -> Boolean.TRUE.equals(result.get("confirmed")))
                .count();
        target.put("itemCount", results.size());
        target.put("confirmedCount", confirmedCount);
    }

    private void moveIfPresent(Map<String, Object> source, Map<String, Object> target, String key) {
        if (source.containsKey(key)) {
            target.put(key, source.remove(key));
        }
    }

    private String stringValue(Object value) {
        return value instanceof String text && !text.isBlank() ? text : null;
    }

    private record EventSemantics(String kind, String phase, String importance) {
    }
}
