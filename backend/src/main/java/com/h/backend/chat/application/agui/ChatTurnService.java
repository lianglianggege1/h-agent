package com.h.backend.chat.application.agui;

import com.h.backend.chat.application.ChatMemorySnapshotService;
import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.chat.infrastructure.persistence.entity.ChatSessionMessageEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.*;
import com.h.backend.common.exception.BusinessException;
import dev.langchain4j.data.message.*;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import java.util.*;

/** Mutations run only after ChatService acquires the session execution permit. */
@Service
public class ChatTurnService {
    private final ChatSessionMessageMapper messages;
    private final AguiRunSnapshotMapper snapshots;
    private final ChatSessionMapper roots;
    private final ChatMemorySnapshotService memory;
    private final ChatMemoryIdFactory memoryIds;
    private final ObjectMapper json;
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private ChatMemorySnapshotMapper memoryScopes;

    public ChatTurnService(ChatSessionMessageMapper messages, AguiRunSnapshotMapper snapshots,
                           ChatSessionMapper roots, ChatMemorySnapshotService memory,
                           ChatMemoryIdFactory memoryIds, ObjectMapper json) {
        this.messages = messages; this.snapshots = snapshots; this.roots = roots;
        this.memory = memory; this.memoryIds = memoryIds; this.json = json;
    }

    @Transactional
    public String prepare(Long userId, String sessionId, String memoryId, AgentDefinition agent,
                          ChatTurnOperation operation, String text) {
        var rows = snapshots.list(userId, sessionId);
        if (operation == null) {
            // Sending new input consumes the old continue action, while keeping the partial answer visible.
            for (var row : rows) if ("PAUSED".equals(row.status())) {
                var state = state(row);
                String partial = partialText(state);
                rebuild(userId, sessionId, memoryId, agent, messages.selectVisibleHistory(sessionId), partial, false);
                state.put("status", "CANCELLED");
                snapshots.update(row.runId(), userId, "CANCELLED", json.writeValueAsString(state));
            }
            return text;
        }
        var latest = messages.selectLatestUser(sessionId);
        if (latest == null || !userId.equals(latest.getUserId()) || !sessionId.equals(latest.getSessionId())
                || !operation.userMessageId().equals(latest.getId()))
            throw new BusinessException(40901, "只能修改或继续当前会话最近一条用户消息，请刷新后重试");
        var history = messages.selectVisibleHistory(sessionId);
        var before = history.stream().filter(row -> row.getSequenceNo() < latest.getSequenceNo()).toList();
        if ("edit".equals(operation.action())) {
            messages.reviseUser(latest.getId(), text);
            messages.supersedeAfter(sessionId, latest.getSequenceNo());
            roots.reviseLastUser(latest.getSessionRecordId(), text);
            for (var row : rows) {
                var state = state(row);
                if (!row.runId().equals(operation.transportRunId())
                        && String.valueOf(latest.getId()).equals(state.get("anchorMessageId"))) retire(userId, row, state);
            }
        } else {
            var source = snapshots.find(userId, operation.sourceRunId());
            if (source == null || !sessionId.equals(source.sessionId())
                    || !("PAUSED".equals(source.status()) || "FAILED".equals(source.status())
                    && Boolean.TRUE.equals(state(source).get("continuable"))))
                throw new BusinessException(40901, "这次回答已无法继续，请刷新后重试");
            // A failed attempt may not have claimed its paused ancestor yet. Consume all
            // continuation checkpoints for this turn so none can reappear after success.
            for (var row : rows) {
                var snapshot = state(row);
                if (!row.runId().equals(operation.transportRunId())
                        && String.valueOf(latest.getId()).equals(snapshot.get("anchorMessageId"))
                        && ("PAUSED".equals(row.status()) || "FAILED".equals(row.status())
                        && Boolean.TRUE.equals(snapshot.get("continuable")))) retire(userId, row, snapshot);
            }
        }
        rebuild(userId, sessionId, memoryId, agent, before, "", "continue".equals(operation.action()));
        if ("edit".equals(operation.action())) return text;
        String results = history.stream().filter(row -> row.getSequenceNo() > latest.getSequenceNo())
                .map(ChatSessionMessageEntity::getContentText).filter(Objects::nonNull)
                .reduce("", (a, b) -> a + "\n" + b);
        var source = rows.stream().filter(row -> row.runId().equals(operation.sourceRunId())).findFirst().orElse(null);
        String tools = source == null ? "" : Objects.toString(state(source).get("continuationContext"), "")
                + "\n" + json.writeValueAsString(state(source).getOrDefault("activities", List.of()));
        return "继续完成原用户请求：\n" + latest.getContentText()
                + "\n\n此前已执行的工具与任务（检查并复用，勿重复提交）：\n" + results + "\n" + tools
                + "\n\n已经向用户输出的回答：\n" + operation.prefix()
                + "\n\n请直接从中断处接着输出剩余内容，不要复述已有文字或说明正在继续。";
    }

    private void retire(Long userId, AguiRunSnapshotMapper.Snapshot row, Map<String, Object> state) {
        state.put("status", "SUPERSEDED");
        state.put("partialMessages", List.of());
        state.put("activities", List.of());
        snapshots.update(row.runId(), userId, "SUPERSEDED", json.writeValueAsString(state));
    }

    private void rebuild(Long userId, String sessionId, String memoryId, AgentDefinition agent,
                         List<ChatSessionMessageEntity> history, String partial, boolean continuing) {
        var visible = history.stream().filter(row -> "user".equals(row.getRoleCode())
                || "assistant".equals(row.getRoleCode()) && !"REASONING".equals(row.getMessageType()))
                .filter(row -> row.getContentText() != null && !row.getContentText().isBlank()).toList();
        if (agent.agentBean() instanceof HarnessAgent harness) {
            var delegate = harness.getDelegate();
            delegate.clearStateCache(String.valueOf(userId), sessionId);
            var old = delegate.getAgentState(String.valueOf(userId), sessionId);
            var builder = io.agentscope.core.state.AgentState.builder().userId(String.valueOf(userId))
                    .sessionId(sessionId).permissionContext(old.getPermissionContext());
            if (continuing) builder.toolContext(old.getToolContext()).tasksContext(old.getTasksContext())
                    .planModeContext(old.getPlanModeContext());
            var state = builder.build();
            for (var row : visible) state.contextMutable().add(Msg.builder()
                    .name("user".equals(row.getRoleCode()) ? "user" : "assistant")
                    .role("user".equals(row.getRoleCode()) ? MsgRole.USER : MsgRole.ASSISTANT)
                    .textContent(row.getContentText()).build());
            if (!partial.isEmpty()) state.contextMutable().add(Msg.builder().name("assistant")
                    .role(MsgRole.ASSISTANT).textContent(partial).build());
            delegate.getStateStore().save(String.valueOf(userId), sessionId, "agent_state", state);
            delegate.clearStateCache(String.valueOf(userId), sessionId);
        } else {
            List<ChatMessage> context = new ArrayList<>();
            for (var row : visible) context.add("user".equals(row.getRoleCode())
                    ? UserMessage.from(row.getContentText()) : AiMessage.from(row.getContentText()));
            if (!partial.isEmpty()) context.add(AiMessage.from(partial));
            if (memoryScopes != null) for (var scope : memoryScopes.selectAllBySessionId(sessionId)) {
                if (agent.agentId().equals(scope.getAgentId())) memory.cacheMemory(
                        new com.h.backend.chat.domain.memory.ChatMemoryContext(userId, scope.getPromptId(),
                                sessionId, scope.getAgentId(), scope.getMemoryScope()), context);
            }
            memory.cacheMemory(memoryIds.parse(memoryId), context);
            memory.flushNow(sessionId);
        }
    }

    private Map<String, Object> state(AguiRunSnapshotMapper.Snapshot row) {
        return json.readValue(row.snapshotJson(), new TypeReference<>() { });
    }

    public static String partialText(Map<String, Object> state) {
        return ((List<?>) state.getOrDefault("partialMessages", List.of())).stream()
                .filter(Map.class::isInstance).map(Map.class::cast)
                .map(value -> Objects.toString(value.get("content"), ""))
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }
}
