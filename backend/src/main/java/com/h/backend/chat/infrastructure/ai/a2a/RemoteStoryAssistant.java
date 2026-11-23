package com.h.backend.chat.infrastructure.ai.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.h.backend.chat.domain.agent.StreamingAgenticChat;
import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.backend.chat.domain.agent.AgentStepEventBridge;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.capabilities.memory.domain.MemoryInvocationContext;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.invocation.InvocationParameters;
import io.jsonwebtoken.Jwts;

import javax.crypto.SecretKey;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.LongPredicate;

public final class RemoteStoryAssistant implements StreamingAgenticChat {
    static final String REMOTE_AGENT = "story-workflow";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<String> endpoint;
    private final SecretKey signingKey;
    private final References references;
    private final LongPredicate longFormAccess;
    private final NacosStoryReview review;
    private final RemoteStoryIllustration illustration;
    private final AgentStepEventBridge events;
    private final AgentObservability observability;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    RemoteStoryAssistant(Supplier<String> endpoint, SecretKey signingKey, References references,
                         LongPredicate longFormAccess, NacosStoryReview review, RemoteStoryIllustration illustration,
                         AgentStepEventBridge events, AgentObservability observability) {
        this.endpoint = endpoint;
        this.signingKey = signingKey;
        this.references = references;
        this.longFormAccess = longFormAccess;
        this.review = review;
        this.illustration = illustration;
        this.events = events;
        this.observability = observability;
    }

    public ResultWithAgenticScope<String> chat(String memoryId, String message, InvocationParameters parameters) {
        return execute(memoryId, message, parameters, null);
    }

    @Override
    public ResultWithAgenticScope<String> chatStreaming(
            String memoryId, String message, InvocationParameters parameters, Consumer<String> chunks) {
        return execute(memoryId, message, parameters, chunks);
    }

    private ResultWithAgenticScope<String> execute(
            String memoryId, String message, InvocationParameters parameters, Consumer<String> chunks) {
        var context = Objects.requireNonNull(MemoryInvocationContext.from(parameters), "Story requires a session");
        long owner = context.userId();
        String session = context.actualSessionId();
        String messageId = "chat-run-" + context.sourceExecutionId();
        if ("/技能".equals(message.strip())) {
            JsonNode card = call(owner, request(messageId, "GetExtendedAgentCard").toString()).path("result");
            if (!card.path("skills").isArray()) throw new IllegalStateException("无法读取远端故事技能");
            var names = new java.util.ArrayList<String>();
            card.path("skills").forEach(skill -> names.add(skill.path("name").asText()));
            return new ResultWithAgenticScope<>(null, "当前可以使用：" + String.join("、", names) + "。"
                    + "输入「/修订 修改要求」继续编辑最近完成的故事。"
                    + "输入「/评分 稿件」或「/审稿 稿件和参考资料」调用远端审核。输入「/配图」为上一段故事生成插画。"
                    + (names.contains("长篇小说模式") ? "输入「/长篇 主题、风格与受众」开始创作。" : ""));
        }
        String trimmed = message.strip();
        if (trimmed.equals("/配图") || trimmed.startsWith("/配图 ") || trimmed.equals("给故事配图")) {
            String description = trimmed.startsWith("/配图 ") ? trimmed.substring(4).strip() : "";
            return new ResultWithAgenticScope<>(null, illustration.run(context, memoryId, description));
        }
        if (trimmed.startsWith("/评分 ") || trimmed.startsWith("/审稿 ")) {
            String skill = trimmed.startsWith("/评分 ") ? "style-score" : "style-review";
            return new ResultWithAgenticScope<>(null, review.run(trimmed.substring(4).strip(), skill));
        }
        var expected = references.get(owner, session);
        boolean revision = trimmed.startsWith("/修订 ");
        var edition = revision ? references.latestEdition(owner, session) : Optional.<Reference>empty();
        if (revision && edition.isEmpty()) return new ResultWithAgenticScope<>(null, "请先完成一篇故事，再发起修订。");
        boolean longForm = message.stripLeading().startsWith("/长篇 ");
        if (longForm && !longFormAccess.test(owner)) {
            return new ResultWithAgenticScope<>(null, "当前账号无权使用长篇小说模式。");
        }
        var input = JSON.createObjectNode().put("messageId", messageId).put("role", "ROLE_USER");
        if (longForm) input.putObject("metadata").put("skillId", "write-novel");
        input.putArray("parts").addObject().put("text", revision ? trimmed.substring(4).strip() : message);
        edition.ifPresent(previous -> {
            input.put("contextId", previous.contextId());
            input.putArray("referenceTaskIds").add(previous.taskId());
        });
        var waiting = revision ? Optional.<Reference>empty() : expected;
        waiting.ifPresent(reference -> {
            input.put("taskId", reference.taskId()).put("contextId", reference.contextId());
            events.emitTool(memoryId, new ChatStreamEvent("a2a_story_working", "", null, reference.taskId()));
        });
        var rpc = request(messageId, chunks == null ? "SendMessage" : "SendStreamingMessage");
        rpc.putObject("params").set("message", input);

        String prefix = revision ? "**修订稿**\n\n" : "";
        var started = new java.util.concurrent.atomic.AtomicBoolean();
        JsonNode response = chunks == null ? call(owner, rpc.toString()) : streamingCall(owner, rpc, new StreamedTask(delta -> {
            if (started.compareAndSet(false, true) && !prefix.isEmpty()) chunks.accept(prefix);
            chunks.accept(delta);
        }));
        if (response.has("error")) {
            if (!revision && response.at("/error/code").asInt() == -32001) references.remove(owner, session);
            throw new IllegalStateException("A2A story request was rejected");
        }
        JsonNode task = response.at("/result/task");
        String taskId = task.path("id").textValue();
        String contextId = task.path("contextId").textValue();
        if (taskId == null || contextId == null || waiting.filter(previous ->
                !previous.taskId().equals(taskId) || !previous.contextId().equals(contextId)).isPresent()) {
            throw new IllegalStateException("A2A story response did not match the expected task");
        }

        String status = task.at("/status/state").asText();
        String reply = switch (status) {
            case "TASK_STATE_INPUT_REQUIRED" -> {
                String question = requiredText(task.at("/status/message/parts/0/text"));
                references.set(owner, session, new Reference(taskId, contextId));
                events.emitTool(memoryId, new ChatStreamEvent("a2a_story_waiting", "", null, taskId));
                yield question;
            }
            case "TASK_STATE_COMPLETED" -> {
                String story = requiredText(task.at("/artifacts/0/parts/0/text"));
                references.recordEdition(owner, session, new Reference(taskId, contextId));
                if (!revision) references.remove(owner, session);
                events.emitTool(memoryId, new ChatStreamEvent("a2a_story_completed", "", null, taskId));
                yield prefix + story;
            }
            case "TASK_STATE_FAILED", "TASK_STATE_REJECTED", "TASK_STATE_CANCELED" -> {
                if (!revision) references.remove(owner, session);
                throw new IllegalStateException("Remote story task ended without a story");
            }
            default -> throw new IllegalStateException("A2A story returned an unexpected state");
        };
        return new ResultWithAgenticScope<>(null, reply);
    }

    private JsonNode streamingCall(long owner, ObjectNode initial, StreamedTask task) {
        String messageId = initial.path("id").asText();
        ObjectNode request = initial;
        for (int attempt = 0; attempt < 3; attempt++) {
            JsonNode error = readStream(owner, request.toString(), task);
            if (error != null && task.current == null) return error;
            if (task.done()) return task.response();
            if (task.current == null) continue;

            String taskId = task.current.path("id").asText();
            var status = request(messageId, "GetTask");
            status.putObject("params").put("id", taskId);
            JsonNode latest = call(owner, status.toString());
            if (latest.has("error")) return latest;
            task.acceptSnapshot(latest.path("result"));
            if (task.done()) return task.response();
            request = request(messageId, "SubscribeToTask");
            request.putObject("params").put("id", taskId);
        }
        throw new IllegalStateException("Remote story stream ended before a task result was available");
    }

    private JsonNode readStream(long owner, String body, StreamedTask task) {
        var span = observability.span(ObservationSpec.of("remote_call a2a.story.stream",
                HObsKind.REMOTE_CALL, "a2a-client", Map.of("agent.id", REMOTE_AGENT)),
                observability.currentContext());
        try {
            var response = http.send(httpRequest(owner, body, "text/event-stream, application/json", span.context()),
                    HttpResponse.BodyHandlers.ofLines());
            try (var lines = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IllegalStateException("A2A story stream failed with HTTP " + response.statusCode());
                }
                if (!response.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
                    JsonNode result = JSON.readTree(lines.reduce("", String::concat));
                    span.succeed();
                    return result;
                }
                var iterator = lines.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next();
                    if (!line.startsWith("data:")) continue;
                    JsonNode responseEvent = JSON.readTree(line.substring(5));
                    if (responseEvent.has("error")) {
                        span.fail(new IllegalStateException("A2A story stream returned a protocol error"));
                        return responseEvent;
                    }
                    task.accept(responseEvent.path("result"));
                    if (task.done()) break;
                }
            }
            span.succeed();
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            span.cancel("interrupted");
            throw new IllegalStateException("A2A story stream interrupted", interrupted);
        } catch (IOException | java.io.UncheckedIOException disconnected) {
            span.fail(disconnected);
            return null;
        } catch (RuntimeException failure) {
            span.fail(failure);
            throw failure;
        }
    }

    private static ObjectNode request(String messageId, String method) {
        return JSON.createObjectNode().put("jsonrpc", "2.0").put("id", messageId).put("method", method);
    }

    private static final class StreamedTask {
        private final Consumer<String> chunks;
        private final StringBuilder text = new StringBuilder();
        private ObjectNode current;

        private StreamedTask(Consumer<String> chunks) {
            this.chunks = chunks;
        }

        private void accept(JsonNode update) {
            if (update.has("task")) {
                acceptSnapshot(update.path("task"));
            } else if (current != null && update.has("artifactUpdate")) {
                var artifact = update.path("artifactUpdate");
                String value = artifact.at("/artifact/parts/0/text").asText();
                if (artifact.path("append").asBoolean()) append(value);
                else reconcile(value);
                current.putArray("artifacts").addObject().putArray("parts").addObject().put("text", text.toString());
            } else if (current != null && update.has("statusUpdate")) {
                current.set("status", update.at("/statusUpdate/status"));
            }
        }

        private void acceptSnapshot(JsonNode snapshot) {
            if (!(snapshot instanceof ObjectNode object) || !object.hasNonNull("id")) {
                throw new IllegalStateException("Invalid remote story task snapshot");
            }
            if (current != null && !current.path("id").equals(object.path("id"))) {
                throw new IllegalStateException("Unexpected remote story task");
            }
            current = object.deepCopy();
            var story = snapshot.at("/artifacts/0/parts/0/text");
            if (story.isTextual()) reconcile(story.textValue());
        }

        private void reconcile(String snapshot) {
            if (!snapshot.startsWith(text.toString())) {
                throw new IllegalStateException("Remote story replaced previously streamed content");
            }
            append(snapshot.substring(text.length()));
        }

        private void append(String delta) {
            if (delta.isEmpty()) return;
            text.append(delta);
            chunks.accept(delta);
        }

        private boolean done() {
            return current != null && switch (current.at("/status/state").asText()) {
                case "TASK_STATE_INPUT_REQUIRED", "TASK_STATE_COMPLETED", "TASK_STATE_FAILED",
                     "TASK_STATE_CANCELED", "TASK_STATE_REJECTED" -> true;
                default -> false;
            };
        }

        private JsonNode response() {
            var response = JSON.createObjectNode();
            response.putObject("result").set("task", current);
            return response;
        }
    }

    private JsonNode call(long owner, String body) {
        var span = observability.span(ObservationSpec.of("remote_call a2a.story", HObsKind.REMOTE_CALL, "a2a-client",
                Map.of("agent.id", REMOTE_AGENT)), observability.currentContext());
        try {
            var response = http.send(httpRequest(owner, body, "application/json", span.context()),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("A2A story request failed with HTTP " + response.statusCode());
            }
            JsonNode result = JSON.readTree(response.body());
            if (result == null || !result.isObject()) throw new IllegalStateException("Invalid A2A story response");
            span.succeed();
            return result;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            span.cancel("interrupted");
            throw new IllegalStateException("A2A story request interrupted", interrupted);
        } catch (IOException | RuntimeException failure) {
            span.fail(failure);
            throw new IllegalStateException("A2A story request failed", failure);
        }
    }

    private HttpRequest httpRequest(long owner, String body, String accept,
                                    com.h.agent.observability.lifecycle.ObservationContext trace) {
        var request = HttpRequest.newBuilder(URI.create(endpoint.get())).timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Accept", accept)
                .header("A2A-Version", "1.0")
                .header("Authorization", "Bearer " + credential(owner))
                .POST(HttpRequest.BodyPublishers.ofString(body));
        Map<String, String> propagation = new HashMap<>();
        observability.inject(trace, propagation);
        propagation.forEach(request::header);
        return request.build();
    }

    private String credential(long owner) {
        Instant now = Instant.now();
        return Jwts.builder().issuer("h-agent").subject(Long.toString(owner))
                .audience().add(REMOTE_AGENT).and()
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(90)))
                .claim("story_long_form", longFormAccess.test(owner))
                .signWith(signingKey, Jwts.SIG.HS256).compact();
    }

    private static String requiredText(JsonNode node) {
        String text = node.textValue();
        if (text == null || text.isBlank()) throw new IllegalStateException("A2A story response has no text");
        return text;
    }

    record Reference(String taskId, String contextId) {}

    interface References {
        Optional<Reference> get(long owner, String session);
        void set(long owner, String session, Reference reference);
        void remove(long owner, String session);
        default Optional<Reference> latestEdition(long owner, String session) { return Optional.empty(); }
        default void recordEdition(long owner, String session, Reference edition) {}
    }
}
