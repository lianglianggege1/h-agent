package com.h.matrixteam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 把一次 agent 执行的过程（思考片段 + 工具步骤）以 AG-UI 风格快照事件
 * com.h.agent.progress 持续发到房间：Matrix 历史即输出记录，前端按 runId 聚合渲染。
 * 快照不可变，每次变更发新事件，前端取最新；上报失败只告警，不打断执行。
 */
public final class TeamProgressReporter {

    public static final String EVENT_TYPE = "com.h.agent.progress";
    private static final Logger log = LoggerFactory.getLogger(TeamProgressReporter.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_RESULT_LENGTH = 2000;
    private static final long REASONING_FLUSH_INTERVAL_MS = 1000;
    private static final long MIN_FLUSH_INTERVAL_MS = 1000;

    private final MatrixClient client;
    private final String roomId;
    private final String runId;
    private final long startedAt = System.currentTimeMillis();

    private final List<Step> steps = new ArrayList<>();
    private final List<Entry> entries = new ArrayList<>();
    private String status = "RUNNING";
    private boolean answerStarted;
    private long lastFlushAt;
    private long processEndedAt;

    private static final class Step {
        final String id;
        final String name;
        String status = "RUNNING";
        String result;

        Step(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private static final class Entry {
        final String id;
        final String type;
        final String stepId;
        final StringBuilder text = new StringBuilder();

        Entry(String id, String type, String stepId) {
            this.id = id;
            this.type = type;
            this.stepId = stepId;
        }
    }

    public TeamProgressReporter(MatrixClient client, String roomId) {
        this.client = client;
        this.roomId = roomId;
        this.runId = UUID.randomUUID().toString();
    }

    public String runId() {
        return runId;
    }

    public synchronized void start() {
        flush();
    }

    public synchronized void reasoning(String delta) {
        if (delta == null || delta.isEmpty()) return;
        Entry last = entries.isEmpty() ? null : entries.get(entries.size() - 1);
        if (last == null || !"reasoning".equals(last.type)) {
            last = new Entry("reasoning-" + entries.size(), "reasoning", null);
            entries.add(last);
        }
        last.text.append(delta);
        long now = System.currentTimeMillis();
        if (now - lastFlushAt >= REASONING_FLUSH_INTERVAL_MS) flush();
    }

    public synchronized void toolStart(String toolCallId, String name) {
        String id = "tool-" + toolCallId;
        steps.add(new Step(id, name));
        entries.add(new Entry("entry-" + entries.size(), "step", id));
        flushThrottled();
    }

    public synchronized void toolResult(String toolCallId, String result, boolean failed) {
        String id = "tool-" + toolCallId;
        for (Step step : steps) {
            if (step.id.equals(id)) {
                step.status = failed ? "FAILED" : "COMPLETED";
                step.result = truncate(result);
                break;
            }
        }
        flushThrottled();
    }

    public synchronized void answerStarted() {
        answerStarted = true;
        flushThrottled();
    }

    public synchronized void finish(String finalStatus) {
        status = finalStatus;
        processEndedAt = System.currentTimeMillis();
        flush();
    }

    private void flushThrottled() {
        if (System.currentTimeMillis() - lastFlushAt >= MIN_FLUSH_INTERVAL_MS) {
            flush();
        }
    }

    private void flush() {
        lastFlushAt = System.currentTimeMillis();
        try {
            client.sendEvent(roomId, EVENT_TYPE, snapshot());
        } catch (RuntimeException e) {
            log.warn("过程快照上报失败 room={} run={}: {}", roomId, runId, e.getMessage());
        }
    }

    private ObjectNode snapshot() {
        ObjectNode content = JSON.createObjectNode();
        content.put("runId", runId);
        content.put("status", status);
        content.put("startedAt", startedAt);
        content.put("answerStarted", answerStarted);
        if (processEndedAt > 0) content.put("processEndedAt", processEndedAt);
        ArrayNode stepArray = content.putArray("steps");
        for (Step step : steps) {
            ObjectNode node = stepArray.addObject();
            node.put("id", step.id);
            node.put("name", step.name);
            node.put("status", step.status);
            node.put("kind", "tool");
            if (step.result != null) node.put("result", step.result);
        }
        ArrayNode entryArray = content.putArray("entries");
        for (Entry entry : entries) {
            ObjectNode node = entryArray.addObject();
            node.put("id", entry.id);
            node.put("type", entry.type);
            if ("reasoning".equals(entry.type)) {
                node.putArray("chunks").add(entry.text.toString());
            } else {
                node.put("stepId", entry.stepId);
            }
        }
        return content;
    }

    private static String truncate(String value) {
        if (value == null) return null;
        return value.length() <= MAX_RESULT_LENGTH ? value : value.substring(0, MAX_RESULT_LENGTH) + "…";
    }
}