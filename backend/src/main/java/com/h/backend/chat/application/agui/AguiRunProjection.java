package com.h.backend.chat.application.agui;

import com.h.backend.chat.interfaces.dto.*;
import io.agentscope.core.agui.adapter.AguiAdapterConfig;
import io.agentscope.core.agui.adapter.strategy.AgentEventConverterRegistry;
import io.agentscope.core.agui.adapter.strategy.AguiStreamContext;
import io.agentscope.core.agui.event.AguiEvent;
import io.agentscope.core.agui.event.AguiEventType;
import io.agentscope.core.agui.model.AguiMessage;
import io.agentscope.core.event.AgentEvent;

import java.util.*;
import java.util.function.Consumer;

/** Product projection over SDK events. No SSE encoding or protocol parsing lives here. */
public final class AguiRunProjection {
    public static final String ACTIVITY_TYPE = "h-agent.progress";
    private final String threadId;
    private final String runId;
    private final Consumer<AguiEvent> output;
    private final AguiStreamContext context;
    private final AgentEventConverterRegistry converters = new AgentEventConverterRegistry();
    private final Map<String, Map<String, Object>> steps = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> activities = new LinkedHashMap<>();
    private final Map<String, StringBuilder> text = new LinkedHashMap<>();
    private boolean harness;
    private String status = "RUNNING";
    private String reasoning = "";
    private Long businessRunId;
    private String anchorMessageId;
    private String anchorPlacement = "AFTER";

    public AguiRunProjection(String threadId, String runId, Consumer<AguiEvent> output) {
        this.threadId = threadId;
        this.runId = runId;
        this.output = output;
        AguiAdapterConfig config = AguiAdapterConfig.builder()
                .emitToolCallArgs(false).enableReasoning(false)
                .baseEventPropertiesEnricherEnabled(false).build();
        context = new AguiStreamContext(threadId, runId, config);
    }

    public String status() { return status; }
    public boolean terminal() { return !"RUNNING".equals(status); }

    public void accept(ChatStreamEvent event) {
        switch (event.type()) {
            case "user_message" -> { if (event.message() != null) anchor(event.message().id(), "AFTER"); }
            case "run_started" -> businessRunId = (Long) event.payload();
            case "agent_event" -> {
                harness = true;
                AgentEvent source = (AgentEvent) event.payload();
                // Binary/data blocks stay in the authorized resource delivery channel, as before migration.
                if (source instanceof io.agentscope.core.event.ToolResultDataDeltaEvent) break;
                for (AguiEvent converted : converters.convert(source, context)) {
                    // The product commit/approval boundary, not AgentEnd, owns the transport terminal.
                    if (converted instanceof AguiEvent.RunStarted || converted instanceof AguiEvent.RunFinished
                            || converted instanceof AguiEvent.Raw || converted instanceof AguiEvent.Custom) continue;
                    publish(converted);
                }
            }
            case "chunk" -> {
                if (!harness && event.content() != null && !event.content().isEmpty()) {
                    context.beginEvent();
                    context.appendTextDelta("answer-" + runId, event.content());
                    context.drainEvents().forEach(this::publish);
                }
            }
            case "reasoning" -> {
                reasoning = limited(reasoning + Objects.toString(event.content(), ""), 16000);
                // Sent together with the next product snapshot; token deltas are not database events.
            }
            case "agent_step" -> {
                AgentStepPayloadDto step = (AgentStepPayloadDto) event.payload();
                String key = "step-" + step.invocationId() + "-" + step.nodeId();
                String phase = Objects.toString(step.status(), "running").toLowerCase(Locale.ROOT);
                step(key, step.nodeName(), phase, null);
                if ("running".equals(phase)) output.accept(new AguiEvent.StepStarted(threadId, runId, key));
                else output.accept(new AguiEvent.StepFinished(threadId, runId, key));
            }
            case "tool_start", "tool_result" -> {
                @SuppressWarnings("unchecked") Map<String, Object> tool = (Map<String, Object>) event.payload();
                String id = Objects.toString(tool.get("id"));
                String name = Objects.toString(tool.get("name"));
                context.beginEvent();
                if ("tool_start".equals(event.type())) {
                    context.startToolCall(id, name);
                    context.appendToolCallArgs(id, "{}");
                    context.endToolCall(id);
                } else {
                    output.accept(new AguiEvent.ToolCallResult(threadId, runId, id,
                            limited(Objects.toString(tool.get("result"), ""), 8000), "tool", "result-" + id));
                    step("tool-" + id, name, Boolean.TRUE.equals(tool.get("failed")) ? "failed" : "completed",
                            limited(Objects.toString(tool.get("result"), ""), 8000));
                }
                context.drainEvents().forEach(this::publish);
            }
            case "harness_event" -> {
                HarnessAgentEventPayload payload = (HarnessAgentEventPayload) event.payload();
                if (payload.projection() != null && payload.projection().subagent() != null) {
                    activity("subagent-" + payload.projection().subagent().sessionId(), "h-agent.subagent",
                            Map.of("subagent", payload.projection().subagent()));
                }
                if ("MODEL_STATUS".equals(payload.kind())) {
                    String id = "model-" + Objects.toString(payload.data().get("agentSessionId"), threadId);
                    step(id, "模型生成", "END".equals(payload.phase()) ? "completed" : "running", null);
                }
            }
            case "image", "resource" -> {
                if (event.message() != null) resource(event.message());
            }
            case "action_required" -> {
                ApprovalRequestDto approval = (ApprovalRequestDto) event.payload();
                activity("approval-" + approval.approvalId(), "h-agent.approval", Map.of("approval", approval));
                status = "WAITING_APPROVAL";
            }
            case "blocked" -> {
                step("blocked", "请求未通过检查", "failed", event.content());
                status = "BLOCKED";
            }
            case "error" -> {
                step("error", "任务失败", "failed", event.content());
                status = "FAILED";
            }
            case "done" -> {
                status = "COMPLETED";
                // The persisted product message replaces all transient answer IDs at commit.
                text.clear();
            }
            default -> { }
        }
    }

    private void publish(AguiEvent event) {
        if (event instanceof AguiEvent.ToolCallResult result) {
            event = new AguiEvent.ToolCallResult(threadId, runId, result.toolCallId(),
                    limited(result.content(), 8000), result.role(), result.messageId());
        }
        if (event instanceof AguiEvent.TextMessageStart start) text.putIfAbsent(start.messageId(), new StringBuilder());
        if (event instanceof AguiEvent.TextMessageContent content) {
            text.computeIfAbsent(content.messageId(), ignored -> new StringBuilder()).append(content.delta());
        }
        if (event instanceof AguiEvent.ToolCallStart tool) step("tool-" + tool.toolCallId(), tool.toolCallName(), "running", null);
        if (event instanceof AguiEvent.ToolCallResult result) {
            Map<String, Object> previous = steps.get("tool-" + result.toolCallId());
            step("tool-" + result.toolCallId(), previous == null ? "工具" : Objects.toString(previous.get("name")),
                    "completed", limited(result.content(), 8000));
        }
        output.accept(event);
    }

    private void step(String id, String name, String phase, String result) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("name", name);
        value.put("status", phase);
        if (result != null && !result.isBlank()) value.put("result", result);
        steps.put(id, value);
    }

    public void resource(ChatSessionMessageDto message) {
        activity("resource-" + message.id(), "h-agent.resource", Map.of("message", message,
                "anchorMessageId", message.id(), "anchorPlacement", "AFTER"));
    }

    public void anchor(String messageId, String placement) {
        anchorMessageId = messageId;
        anchorPlacement = placement;
    }

    public void activity(String id, String type, Map<String, Object> content) {
        Map<String, Object> anchored = new LinkedHashMap<>(content);
        if (anchorMessageId != null && !anchored.containsKey("anchorMessageId")) {
            anchored.put("anchorMessageId", anchorMessageId);
            anchored.put("anchorPlacement", anchorPlacement);
        }
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", id);
        value.put("activityType", type);
        value.put("content", anchored);
        activities.put(id, value);
        output.accept(new AguiEvent.ActivitySnapshot(threadId, runId, id, type, anchored, true));
    }

    public void refreshProgress() {
        if (terminal()) steps.values().forEach(step -> {
            if ("running".equalsIgnoreCase(Objects.toString(step.get("status")))) {
                step.put("status", "COMPLETED".equals(status) ? "completed" : "WAITING_APPROVAL".equals(status) ? "waiting" : "cancelled");
            }
        });
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("runId", runId);
        progress.put("status", status);
        progress.put("steps", List.copyOf(steps.values()));
        progress.put("reasoning", reasoning);
        activity("progress-" + runId, ACTIVITY_TYPE, progress);
    }

    public void stop() {
        status = "CANCELLED";
        steps.values().forEach(step -> {
            if ("running".equalsIgnoreCase(Objects.toString(step.get("status")))) step.put("status", "cancelled");
        });
    }

    public void interrupted() { status = "INTERRUPTED"; }

    public void closeMessages() { context.finishPendingEvents().forEach(this::publish); }

    public List<AguiMessage> partialMessages() {
        return text.entrySet().stream().filter(entry -> !entry.getValue().isEmpty())
                .map(entry -> AguiMessage.assistantMessage(entry.getKey(), entry.getValue().toString())).toList();
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("runId", runId);
        snapshot.put("threadId", threadId);
        snapshot.put("status", status);
        snapshot.put("activities", List.copyOf(activities.values()));
        snapshot.put("partialMessages", text.entrySet().stream().map(entry ->
                Map.of("id", entry.getKey(), "content", entry.getValue().toString())).toList());
        if (businessRunId != null) snapshot.put("businessRunId", businessRunId);
        if (anchorMessageId != null) {
            snapshot.put("anchorMessageId", anchorMessageId);
            snapshot.put("anchorPlacement", anchorPlacement);
        }
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    public void restore(Map<String, Object> snapshot) {
        status = Objects.toString(snapshot.get("status"), "INTERRUPTED");
        if (snapshot.get("businessRunId") instanceof Number id) businessRunId = id.longValue();
        if (snapshot.get("anchorMessageId") instanceof String id) {
            anchorMessageId = id;
            anchorPlacement = Objects.toString(snapshot.get("anchorPlacement"), "AFTER");
        }
        for (Object raw : (List<?>) snapshot.getOrDefault("activities", List.of())) {
            Map<String, Object> activity = new LinkedHashMap<>((Map<String, Object>) raw);
            activities.put(Objects.toString(activity.get("id")), activity);
            if (ACTIVITY_TYPE.equals(activity.get("activityType"))) {
                Map<String, Object> progress = (Map<String, Object>) activity.get("content");
                reasoning = Objects.toString(progress.get("reasoning"), "");
                for (Object value : (List<?>) progress.getOrDefault("steps", List.of())) {
                    Map<String, Object> step = new LinkedHashMap<>((Map<String, Object>) value);
                    steps.put(Objects.toString(step.get("id")), step);
                }
            }
        }
        for (Object raw : (List<?>) snapshot.getOrDefault("partialMessages", List.of())) {
            Map<String, Object> message = (Map<String, Object>) raw;
            text.put(Objects.toString(message.get("id")), new StringBuilder(Objects.toString(message.get("content"), "")));
        }
    }

    public List<AguiEvent> activityEvents() { return activityEvents(threadId, runId, snapshot()); }

    @SuppressWarnings("unchecked")
    public static List<AguiEvent> activityEvents(String threadId, String runId, Map<String, Object> snapshot) {
        List<AguiEvent> events = new ArrayList<>();
        for (Object item : (List<?>) snapshot.getOrDefault("activities", List.of())) {
            Map<String, Object> activity = (Map<String, Object>) item;
            events.add(new AguiEvent.ActivitySnapshot(threadId, runId,
                    Objects.toString(activity.get("id")), Objects.toString(activity.get("activityType")),
                    (Map<String, Object>) activity.get("content"), true));
        }
        return events;
    }

    private static String limited(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
