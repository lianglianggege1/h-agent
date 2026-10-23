package com.h.backend.chat.application.agui;

import com.h.backend.chat.infrastructure.persistence.mapper.AguiRunSnapshotMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatSessionMessageMapper;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import io.agentscope.core.event.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;
import com.h.backend.chat.domain.agent.HarnessExecutionRegistry;
import jakarta.annotation.PreDestroy;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ConcurrentHashMap;

/** Persist the same product projection for delegated children that have no browser-started turn. */
@Component
@Slf4j
public final class AguiSubagentRecorder {
    private record Recording(Long userId, String sessionId, String runId, AguiRunProjection projection) { }
    private final AguiRunSnapshotMapper snapshots;
    private final ObjectMapper json;
    private final HarnessExecutionRegistry executions;
    private final ChatSessionMessageMapper messages;
    private final Disposable heartbeat;
    private final ConcurrentHashMap<String, Recording> recordings = new ConcurrentHashMap<>();

    public AguiSubagentRecorder(AguiRunSnapshotMapper snapshots, ObjectMapper json, HarnessExecutionRegistry executions,
                               ChatSessionMessageMapper messages) {
        this.snapshots = snapshots;
        this.json = json;
        this.executions = executions;
        this.messages = messages;
        heartbeat = Flux.interval(Duration.ofSeconds(2)).subscribe(tick -> checkpoint());
    }

    public synchronized void record(String userId, String sessionId, AgentEvent event) {
        String key = userId + ":" + sessionId;
        Recording recording = recordings.get(key);
        if (recording == null && event instanceof AgentStartEvent start) {
            Long owner = Long.valueOf(userId);
            if (snapshots.list(owner, sessionId).stream().anyMatch(row -> "RUNNING".equals(row.status()))) return;
            String runId = "child-" + java.util.UUID.randomUUID();
            recording = new Recording(owner, sessionId, runId, new AguiRunProjection(sessionId, runId, ignored -> { }));
            try {
                snapshots.insert(new AguiRunSnapshotMapper.Snapshot(runId, sessionId, owner, "delegated", "RUNNING",
                        json.writeValueAsString(recording.projection.snapshot()), null));
            } catch (DuplicateKeyException ex) { return; }
            recordings.put(key, recording);
        }
        if (recording == null) return;
        recording.projection.accept(new ChatStreamEvent("agent_event", "", null, event));
        if (event instanceof ThinkingBlockDeltaEvent thinking) recording.projection.accept(new ChatStreamEvent("reasoning", thinking.getDelta()));
        if (event instanceof AgentEndEvent) {
            var committed = messages.selectLatestByAgentSessionId(sessionId);
            if (committed != null && "assistant".equals(committed.getRoleCode())) {
                recording.projection.anchor(String.valueOf(committed.getId()), "BEFORE");
            }
            recording.projection.accept(new ChatStreamEvent("done", ""));
            recording.projection.closeMessages();
        }
        if (!(event instanceof TextBlockDeltaEvent || event instanceof ThinkingBlockDeltaEvent
                || event instanceof ToolCallDeltaEvent || event instanceof ToolResultTextDeltaEvent)) {
            save(recording);
        }
        if (recording.projection.terminal()) recordings.remove(key, recording);
    }

    public synchronized void fail(String userId, String sessionId, boolean cancelled, String message) {
        Recording recording = recordings.remove(userId + ":" + sessionId);
        if (recording == null) return;
        if (cancelled) recording.projection.stop();
        else recording.projection.accept(new ChatStreamEvent("error", message));
        recording.projection.closeMessages();
        save(recording);
    }

    private void save(Recording recording) {
        recording.projection.refreshProgress();
        snapshots.update(recording.runId, recording.userId, recording.projection.status(),
                json.writeValueAsString(recording.projection.snapshot()));
    }

    private synchronized void checkpoint() {
        for (var recording : java.util.List.copyOf(recordings.values())) {
            try {
            var row = snapshots.find(recording.userId, recording.runId);
            if (row == null) continue;
            if ("CANCEL_REQUESTED".equals(row.status()) || "INTERRUPTED".equals(row.status())) {
                executions.stop(String.valueOf(recording.userId), recording.sessionId);
                fail(String.valueOf(recording.userId), recording.sessionId, true, "任务已停止");
            } else save(recording);
            } catch (RuntimeException error) {
                log.warn("Unable to checkpoint delegated run runId={}", recording.runId, error);
            }
        }
    }

    @PreDestroy
    void close() { heartbeat.dispose(); }
}
