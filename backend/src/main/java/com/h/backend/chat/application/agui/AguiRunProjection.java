package com.h.backend.chat.application.agui;

import com.h.backend.chat.interfaces.dto.*;
import com.agui.community.core.event.*;
import com.agui.community.core.message.AssistantMessage;
import com.agui.community.core.message.Message;
import com.agui.community.core.message.Role;
import io.agentscope.core.event.AgentEvent;

import java.util.*;
import java.util.function.Consumer;

/** Product projection over SDK events. No SSE encoding or protocol parsing lives here. */
public final class AguiRunProjection {
    public static final String ACTIVITY_TYPE = "h-agent.progress";
    private final String threadId;
    private final String runId;
    private final Consumer<Event> output;
    private HarnessAguiAdapter harnessAdapter;
    private final Set<String> openText = new LinkedHashSet<>();
    private final Map<String, Map<String, Object>> steps = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> activities = new LinkedHashMap<>();
    private final Map<String, StringBuilder> text = new LinkedHashMap<>();
    private boolean harness;
    private String status = "RUNNING";
    private String reasoning = "";
    private Long businessRunId;
    private String anchorMessageId;
    private String anchorPlacement = "AFTER";

    public AguiRunProjection(String threadId, String runId, Consumer<Event> output) {
        this.threadId = threadId;
        this.runId = runId;
        this.output = output;
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
                if (harnessAdapter == null) harnessAdapter = new HarnessAguiAdapter(threadId, runId);
                harnessAdapter.accept(source).forEach(this::publish);
            }
            case "chunk" -> {
                if (!harness && event.content() != null && !event.content().isEmpty()) {
                    String id = "answer-" + runId;
                    if (!openText.contains(id)) publish(new TextMessageStartEvent(id, Role.ASSISTANT));
                    publish(new TextMessageContentEvent(id, event.content()));
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
                if ("running".equals(phase)) output.accept(new StepStartedEvent(key));
                else output.accept(new StepFinishedEvent(key));
            }
            case "tool_start", "tool_result" -> {
                @SuppressWarnings("unchecked") Map<String, Object> tool = (Map<String, Object>) event.payload();
                String id = Objects.toString(tool.get("id"));
                String name = Objects.toString(tool.get("name"));
                if ("tool_start".equals(event.type())) {
                    publish(new ToolCallStartEvent(id, name));
                    publish(new ToolCallArgsEvent(id, "{}"));
                    publish(new ToolCallEndEvent(id));
                } else {
                    output.accept(new ToolCallResultEvent("result-" + id, id,
                            limited(Objects.toString(tool.get("result"), ""), 8000)));
                    step("tool-" + id, name, Boolean.TRUE.equals(tool.get("failed")) ? "failed" : "completed",
                            limited(Objects.toString(tool.get("result"), ""), 8000));
                }
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

    private void publish(Event event) {
        if (event instanceof ToolCallResultEvent result) {
            orderResult(result.content());
            event = new ToolCallResultEvent(result.messageId(), result.toolCallId(),
                    limited(result.content(), 8000), result.role(), null, null);
        }
        if (event instanceof TextMessageStartEvent start) {
            openText.add(start.messageId());
            text.putIfAbsent(start.messageId(), new StringBuilder());
        }
        if (event instanceof TextMessageEndEvent end) openText.remove(end.messageId());
        if (event instanceof TextMessageContentEvent content) {
            text.computeIfAbsent(content.messageId(), ignored -> new StringBuilder()).append(content.delta());
        }
        if (event instanceof ToolCallStartEvent tool) step("tool-" + tool.toolCallId(), tool.toolCallName(), "running", null);
        if (event instanceof ToolCallResultEvent result) {
            Map<String, Object> previous = steps.get("tool-" + result.toolCallId());
            step("tool-" + result.toolCallId(), previous == null ? "工具" : Objects.toString(previous.get("name")),
                    "completed", limited(result.content(), 8000));
        }
        output.accept(event);
    }

    private void orderResult(String content) {
        if (content == null || !content.contains("order-demo")) return;
        try {
            var json = new com.fasterxml.jackson.databind.ObjectMapper();
            var node = json.readTree(content);
            if (node.isTextual()) node = json.readTree(node.textValue());
            if (node.isArray() && !node.isEmpty() && node.get(0).has("text")) node = json.readTree(node.get(0).get("text").asText());
            if (!"order-demo".equals(node.path("kind").asText())) return;
            orders(json.convertValue(node, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { }));
        } catch (RuntimeException | java.io.IOException ignored) { /* Ordinary tool output is not a business card. */ }
    }

    public void orders(Map<String,Object> payload) {
        if (!"order-demo".equals(payload.get("kind")) || !(payload.get("orders") instanceof List<?> orders)) return;
        for (Object raw : orders) {
            if (!(raw instanceof Map<?,?> order) || order.get("id") == null) continue;
            String id = Objects.toString(order.get("id"));
            Map<String,Object> content = Map.of("order",order,"generation",payload.get("generation"),"threadId",threadId);
            activity("order-"+threadId+"-"+id,"h-agent.order",content);
            if(order.get("tickets") instanceof List<?> tickets) for(Object rawTicket:tickets){
                if(!(rawTicket instanceof Map<?,?> ticket) || ticket.get("id")==null)continue;
                Map<String,Object> ticketContent=new LinkedHashMap<>(content);ticketContent.put("ticketId",ticket.get("id"));
                activity("ticket-"+threadId+"-"+ticket.get("id"),"h-agent.ticket",ticketContent);
            }
            if(order.get("decisions") instanceof List<?> decisions) for(Object rawDecision:decisions){
                if(!(rawDecision instanceof Map<?,?> decision) || decision.get("id")==null)continue;
                Map<String,Object> decisionContent=new LinkedHashMap<>(content);decisionContent.put("decisionId",decision.get("id"));
                activity("order-decision-"+threadId+"-"+decision.get("id"),"h-agent.order-decision",decisionContent);
            }
        }
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
        output.accept(new ActivitySnapshotEvent(id, type, anchored));
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

    public void closeMessages() {
        if (harnessAdapter != null) harnessAdapter.finish().forEach(this::publish);
        for (String id : List.copyOf(openText)) publish(new TextMessageEndEvent(id));
    }

    public List<Message> partialMessages() {
        return text.entrySet().stream().filter(entry -> !entry.getValue().isEmpty())
                .<Message>map(entry -> new AssistantMessage(entry.getKey(), entry.getValue().toString())).toList();
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

    public List<Event> activityEvents() { return activityEvents(snapshot()); }

    @SuppressWarnings("unchecked")
    public static List<Event> activityEvents(Map<String, Object> snapshot) {
        List<Event> events = new ArrayList<>();
        for (Object item : (List<?>) snapshot.getOrDefault("activities", List.of())) {
            Map<String, Object> activity = (Map<String, Object>) item;
            events.add(new ActivitySnapshotEvent(
                    Objects.toString(activity.get("id")), Objects.toString(activity.get("activityType")),
                    (Map<String, Object>) activity.get("content")));
        }
        return events;
    }

    private static String limited(String value, int max) {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
