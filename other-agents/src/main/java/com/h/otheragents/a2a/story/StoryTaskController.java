package com.h.otheragents.a2a.story;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.h.agent.observability.lifecycle.ExecutionContextSnapshot;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.observability.A2AObservabilityWebFilter;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentSkill;
import org.a2aproject.sdk.spec.HTTPAuthSecurityScheme;
import org.a2aproject.sdk.spec.SecurityRequirement;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import java.util.Map;
import java.util.Optional;

@RestController
@ConditionalOnProperty(prefix = "other-agents.a2a.story", name = "enabled", havingValue = "true")
public final class StoryTaskController {
    public static final String AGENT_ID = "story-workflow";
    private static final String PATH = "/a2a/agents/" + AGENT_ID;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final OtherAgentsA2AProperties properties;
    private final StoryTaskWorkflow workflow;
    private final CallerIdentity identity;

    StoryTaskController(OtherAgentsA2AProperties properties, StoryTaskWorkflow workflow,
                        CallerIdentity identity) {
        this.properties = properties;
        this.workflow = workflow;
        this.identity = identity;
    }

    @GetMapping(value = PATH + "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String publicCard() throws JsonProcessingException {
        return JSON.writeValueAsString(card());
    }

    public AgentCard card() {
        String endpoint = properties.agentUrl(AGENT_ID);
        return AgentCard.builder()
                .name(AGENT_ID)
                .description("多轮收集主题、风格和受众，完成故事创作")
                .version(properties.getVersion())
                .url(endpoint)
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", endpoint)))
                .capabilities(AgentCapabilities.builder().streaming(true).extendedAgentCard(true).build())
                .securitySchemes(Map.of("bearer", new HTTPAuthSecurityScheme("JWT", "Bearer", null)))
                .securityRequirements(List.of(new SecurityRequirement(Map.of("bearer", List.of()))))
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(List.of(AgentSkill.builder().id("write-story").name("故事创作")
                        .description("根据主题、风格与目标受众创作故事").tags(List.of("writing", "story")).build()))
                .build();
    }

    @PostMapping(value = PATH)
    public Mono<ResponseEntity<?>> request(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                                                String authorization, @RequestBody String payload,
                                                ServerWebExchange exchange) {
        Optional<Caller> owner = authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)
                ? identity.verify(authorization.substring(7).strip()) : Optional.empty();
        if (owner.isEmpty()) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .header(HttpHeaders.WWW_AUTHENTICATE, "Bearer").build());
        }
        ObservationContext observation = exchange.getAttribute(A2AObservabilityWebFilter.EXCHANGE_ATTRIBUTE);
        return Mono.<ResponseEntity<?>>fromCallable(() -> withObservation(observation,
                        () -> handle(owner.get(), payload, observation)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ResponseEntity<?> handle(Caller owner, String payload, ObservationContext observation) {
        JsonNode id = JSON.nullNode();
        try {
            JsonNode request = JSON.readTree(payload);
            if (request == null || !request.isObject() || !"2.0".equals(request.path("jsonrpc").asText())) {
                return json(error(id, -32600, "Invalid request"));
            }
            id = request.path("id").isMissingNode() ? JSON.nullNode() : request.get("id");
            JsonNode params = request.path("params");
            String method = request.path("method").asText();
            if ("SendStreamingMessage".equals(method)) {
                var submission = begin(owner, params.path("message"));
                start(submission, observation);
                return stream(id, submission.task());
            }
            if ("SubscribeToTask".equals(method)) {
                var task = permitted(owner, required(params, "id", 128));
                if (task.state().terminal()) {
                    throw new StoryTaskWorkflow.RpcError(-32004, "Completed tasks can be read via GetTask");
                }
                return stream(id, task);
            }
            JsonNode result = switch (method) {
                case "SendMessage" -> {
                    var submission = begin(owner, params.path("message"));
                    boolean async = params.at("/configuration/returnImmediately").asBoolean(false);
                    var task = submission.task();
                    if (async) start(submission, observation);
                    else if (submission.claimed()) task = workflow.run(task);
                    else task = await(task);
                    yield JSON.createObjectNode().set("task", task(task));
                }
                case "GetTask" -> task(permitted(owner, required(params, "id", 128)));
                case "CancelTask" -> {
                    String taskId = required(params, "id", 128);
                    permitted(owner, taskId);
                    yield task(workflow.cancel(owner.id(), taskId));
                }
                case "GetExtendedAgentCard" -> JSON.valueToTree(extendedCard(owner));
                default -> throw new StoryTaskWorkflow.RpcError(-32601, "Method not found");
            };
            return json(envelope(id).set("result", result).toString());
        } catch (StoryTaskWorkflow.RpcError error) {
            return json(error(id, error.code, error.getMessage()));
        } catch (JsonProcessingException malformed) {
            return json(error(id, -32700, "Parse error"));
        } catch (RuntimeException failure) {
            LoggerFactory.getLogger(StoryTaskController.class).error("A2A story request failed", failure);
            return json(error(id, -32603, "Story request failed"));
        }
    }

    private StoryTaskWorkflow.Task await(StoryTaskWorkflow.Task task) {
        while (!task.state().streamDone()) {
            try {
                Thread.sleep(Duration.ofMillis(120));
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new StoryTaskWorkflow.RpcError(-32603, "Story observer interrupted");
            }
            task = workflow.get(task.owner(), task.id());
        }
        return task;
    }

    private void start(StoryTaskWorkflow.Submission submission, ObservationContext observation) {
        if (!submission.claimed()) return;
        // The Task outlives its observers. Every subsequent subscriber reads the same persisted progress.
        Mono.fromCallable(() -> withObservation(observation, () -> workflow.run(submission.task())))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(ignored -> { }, failure -> {
                    try (var scope = ExecutionContextSnapshot.forObservation(observation).scope()) {
                        LoggerFactory.getLogger(StoryTaskController.class).error("A2A story worker failed task={}",
                                submission.task().id(), failure);
                    }
                });
    }

    private <T> T withObservation(ObservationContext observation, Supplier<T> action) {
        try (var scope = ExecutionContextSnapshot.forObservation(observation).scope()) {
            return action.get();
        }
    }

    private ResponseEntity<?> stream(JsonNode id, StoryTaskWorkflow.Task initial) {
        var first = JSON.createObjectNode().set("task", task(initial));
        Flux<ServerSentEvent<String>> events = Flux.just(event(id, first));
        if (!initial.state().streamDone()) {
            Flux<StoryTaskWorkflow.Task> snapshots = Flux.interval(Duration.ofMillis(150))
                    .onBackpressureLatest()
                    .concatMap(ignored -> Mono.fromCallable(() -> workflow.get(initial.owner(), initial.id()))
                            .subscribeOn(Schedulers.boundedElastic()))
                    .startWith(initial)
                    .distinctUntilChanged(StoryTaskWorkflow.Task::revision)
                    .takeUntil(snapshot -> snapshot.state().streamDone());
            events = events.concatWith(snapshots.buffer(2, 1).concatMap(pair -> pair.size() == 2
                    ? Flux.fromIterable(updates(id, pair.getFirst(), pair.getLast())) : Flux.empty()));
        }
        return ResponseEntity.ok().contentType(MediaType.TEXT_EVENT_STREAM).body(events);
    }

    private static List<ServerSentEvent<String>> updates(JsonNode id, StoryTaskWorkflow.Task previous,
                                                         StoryTaskWorkflow.Task current) {
        var events = new ArrayList<ServerSentEvent<String>>();
        String before = artifactText(previous);
        String after = artifactText(current);
        boolean done = current.state() == StoryTaskWorkflow.State.COMPLETED;
        if (!after.isEmpty() && (!after.equals(before) || done)) {
            boolean append = !before.isEmpty() && after.startsWith(before);
            ObjectNode update = JSON.createObjectNode();
            var artifactEvent = update.putObject("artifactUpdate").put("taskId", current.id())
                    .put("contextId", current.contextId()).put("append", append).put("lastChunk", done);
            artifactEvent.set("artifact", artifact(current, append ? after.substring(before.length()) : after));
            events.add(event(id, update));
        }
        if (previous.state() != current.state()) {
            var update = JSON.createObjectNode();
            update.putObject("statusUpdate").put("taskId", current.id()).put("contextId", current.contextId())
                    .set("status", task(current).get("status"));
            events.add(event(id, update));
        }
        return events;
    }

    private static ServerSentEvent<String> event(JsonNode id, JsonNode result) {
        return ServerSentEvent.builder(envelope(id).set("result", result).toString()).build();
    }

    private static ResponseEntity<String> json(String body) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    private StoryTaskWorkflow.Submission begin(Caller owner, JsonNode message) {
        String messageId = required(message, "messageId", 128);
        if (!"ROLE_USER".equals(message.path("role").asText())) {
            throw new StoryTaskWorkflow.RpcError(-32602, "Expected a user message");
        }
        JsonNode parts = message.path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new StoryTaskWorkflow.RpcError(-32602, "Expected message parts");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            if (!part.path("text").isTextual()) {
                throw new StoryTaskWorkflow.RpcError(-32005, "Story messages support text parts");
            }
            if (!text.isEmpty()) text.append('\n');
            String value = part.get("text").textValue();
            if (value.length() > 10_000 - text.length()) {
                throw new StoryTaskWorkflow.RpcError(-32602, "Story message exceeds text limit");
            }
            text.append(value);
        }
        if (text.toString().isBlank()) throw new StoryTaskWorkflow.RpcError(-32602, "Story message is blank");
        String skill = message.at("/metadata/skillId").asText("write-story");
        if (!List.of("write-story", "write-novel").contains(skill)) {
            throw new StoryTaskWorkflow.RpcError(-32602, "Unknown story skill");
        }
        String parent = message.at("/referenceTaskIds/0").textValue();
        if (parent != null && (parent.isBlank() || parent.length() > 128)) {
            throw new StoryTaskWorkflow.RpcError(-32602, "Invalid story reference");
        }
        return workflow.begin(owner.id(), optional(message, "taskId"), optional(message, "contextId"),
                messageId, text.toString(), "write-novel".equals(skill), owner.longForm(), parent);
    }

    private StoryTaskWorkflow.Task permitted(Caller owner, String taskId) {
        var task = workflow.get(owner.id(), taskId);
        if (task.longForm() && !owner.longForm()) {
            throw new StoryTaskWorkflow.RpcError(-32001, "Task not found");
        }
        return task;
    }

    private AgentCard extendedCard(Caller owner) {
        AgentCard base = card();
        if (!owner.longForm()) return base;
        var skills = new ArrayList<>(base.skills());
        skills.add(AgentSkill.builder().id("write-novel").name("长篇小说模式")
                .description("围绕选定主题、风格和受众创作一章完整的长篇小说")
                .tags(List.of("writing", "novel")).build());
        return AgentCard.builder(base).skills(skills).build();
    }

    private static String optional(JsonNode node, String field) {
        return node.has(field) ? required(node, field, 128) : null;
    }

    private static String required(JsonNode node, String field, int maxLength) {
        String value = node.path(field).textValue();
        if (value == null || value.isBlank() || value.length() > maxLength || value.chars().anyMatch(c -> c < 32)) {
            throw new StoryTaskWorkflow.RpcError(-32602, "Invalid " + field);
        }
        return value;
    }

    private static ObjectNode task(StoryTaskWorkflow.Task story) {
        var result = JSON.createObjectNode().put("id", story.id()).put("contextId", story.contextId());
        if (story.parentTaskId() != null) result.putObject("metadata").put("revisesTaskId", story.parentTaskId());
        var status = result.putObject("status").put("state", "TASK_STATE_" + story.state().name())
                .put("timestamp", story.timestamp());
        if (!artifactText(story).isEmpty()) {
            result.putArray("artifacts").add(artifact(story, artifactText(story)));
        } else if (story.output() != null) {
            var message = status.putObject("message")
                    .put("messageId", story.id() + "-" + story.revision())
                    .put("role", "ROLE_AGENT")
                    .put("taskId", story.id())
                    .put("contextId", story.contextId());
            message.putArray("parts").addObject().put("text", story.output());
        }
        return result;
    }

    private static String artifactText(StoryTaskWorkflow.Task task) {
        if (task.state() != StoryTaskWorkflow.State.WORKING && task.state() != StoryTaskWorkflow.State.COMPLETED) {
            return "";
        }
        return task.output() == null ? "" : task.output();
    }

    private static ObjectNode artifact(StoryTaskWorkflow.Task task, String text) {
        var artifact = JSON.createObjectNode().put("artifactId", "story-" + task.id())
                .put("name", task.parentTaskId() == null ? "故事初稿" : "故事修订稿");
        artifact.putArray("parts").addObject().put("text", text);
        return artifact;
    }

    private static ObjectNode envelope(JsonNode id) {
        return JSON.createObjectNode().put("jsonrpc", "2.0").set("id", id);
    }

    private static String error(JsonNode id, int code, String text) {
        ObjectNode response = envelope(id);
        response.putObject("error").put("code", code).put("message", text);
        return response.toString();
    }

    record Caller(String id, boolean longForm) {}

    @FunctionalInterface
    interface CallerIdentity {
        Optional<Caller> verify(String bearerToken);
    }
}
