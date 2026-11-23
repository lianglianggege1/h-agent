package com.h.backend.chat.infrastructure.ai.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.h.backend.chat.domain.agent.StreamingAgenticChat;
import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.backend.chat.domain.agent.AgentStepEventBridge;
import com.h.backend.chat.domain.agent.AgentStepListener;
import com.h.backend.chat.domain.agent.AgentTopologyProvider;
import com.h.backend.chat.infrastructure.ai.a2a.StoryIntentRouter.Decision;
import com.h.backend.chat.infrastructure.ai.a2a.StoryIntentRouter.Intent;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.capabilities.memory.domain.MemoryInvocationContext;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.scope.AgenticScope;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.function.Consumer;
import java.util.function.LongPredicate;
import java.util.function.BiFunction;

public final class RemoteStoryAssistant implements StreamingAgenticChat, AgentTopologyProvider {
    static final String REMOTE_AGENT = "story-workflow";
    private static final String INVOCATION = "@storyInvocation";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<String> endpoint;
    private final SecretKey signingKey;
    private final References references;
    private final LongPredicate longFormAccess;
    private final NacosStoryReview review;
    private final RemoteStoryIllustration illustration;
    private final AgentStepEventBridge events;
    private final AgentObservability observability;
    private final StoryIntentRouter intentRouter;
    private final UntypedAgent workflow;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    RemoteStoryAssistant(Supplier<String> endpoint, SecretKey signingKey, References references,
                         LongPredicate longFormAccess, NacosStoryReview review, RemoteStoryIllustration illustration,
                         AgentStepEventBridge events, AgentObservability observability, StoryIntentRouter intentRouter) {
        this.endpoint = endpoint;
        this.signingKey = signingKey;
        this.references = references;
        this.longFormAccess = longFormAccess;
        this.review = review;
        this.illustration = illustration;
        this.events = events;
        this.observability = observability;
        this.intentRouter = intentRouter;
        this.workflow = buildWorkflow();
    }

    @Override
    public AgentInstance executionGraph() {
        return (AgentInstance) workflow;
    }

    private UntypedAgent buildWorkflow() {
        var listener = new AgentStepListener(events) {
            @Override
            protected Object executionMemoryId(AgenticScope scope) {
                // Keep the planner scope turn-local, while publishing to the owning chat stream.
                return scope.readState(INVOCATION) instanceof Invocation run ? run.memoryId() : null;
            }
        };
        var classifier = AgenticServices.nonAiAgentBuilder(scope -> classify((Invocation) scope.readState(INVOCATION)))
                .name("识别用户意图").description("结合当前任务状态理解自然语言请求")
                .inputKey(String.class, "message").inputKey(Invocation.class, INVOCATION)
                .outputKey("intent").outputType(Decision.class).listener(listener).build();
        var routes = List.of(
                new Route(Intent.SKILLS, "了解可用技能", "读取授权技能", "通过 A2A 获取当前用户的扩展卡片",
                        (run, decision) -> skills(run)),
                new Route(Intent.ILLUSTRATION, "请求插画", "远端插画", "委派插画 Agent 并交付图片",
                        this::illustrate),
                new Route(Intent.SCORE, "请求评分", "远端稿件评分", "通过 Nacos 发现评分技能并提交稿件",
                        (run, decision) -> review(run, decision, "style-score")),
                new Route(Intent.REVIEW, "请求审稿或事实核查", "远端稿件审核", "通过 Nacos 发现审核技能并提交稿件",
                        (run, decision) -> review(run, decision, "style-review")),
                new Route(Intent.REVISION, "修改已完成故事", "远端故事修订", "引用上一版故事并发起修订",
                        (run, decision) -> story(run, true, false)),
                new Route(Intent.LONG_FORM, "明确请求长篇创作", "授权创作", "核对权限后发起创作任务",
                        (run, decision) -> story(run, false, true)),
                new Route(Intent.DOCUMENT, "请求文档处理", "文档入口指引", "说明可处理文档的会话入口",
                        (run, decision) -> "要解析、复核 PDF 或使用文档画布，请在通用助手或个人 Harness 会话中上传文档。"
                                + "也可以把需要用于创作的文字粘贴到这里。"),
                new Route(Intent.STORY, "发起创作或补充信息", "远端故事任务", "通过 A2A 发起或续接创作并接收任务状态",
                        (run, decision) -> story(run, false, false)));
        var router = AgenticServices.conditionalBuilder()
                .name("委派远端操作").description("按识别出的意图执行对应操作").outputKey("reply");
        for (Route route : routes) {
            router.subAgents(route.condition(), scope -> ((Decision) scope.readState("intent")).intent() == route.intent(),
                    route.agent(listener));
        }
        return AgenticServices.sequenceBuilder()
                .name("A2A 远端故事助手").description("识别自然语言意图并委派远端操作")
                .subAgents(classifier, router.build()).outputKey("reply").build();
    }

    private Decision classify(Invocation run) {
        String text = run.trimmed();
        if (text.equals("/技能")) return new Decision(Intent.SKILLS, false);
        if (text.equals("/配图")) return new Decision(Intent.ILLUSTRATION, true);
        if (text.startsWith("/配图 ")) return new Decision(Intent.ILLUSTRATION, false);
        if (text.startsWith("/评分 ")) return new Decision(Intent.SCORE, false);
        if (text.startsWith("/审稿 ")) return new Decision(Intent.REVIEW, false);
        if (text.startsWith("/修订 ")) return new Decision(Intent.REVISION, false);
        if (text.startsWith("/长篇 ")) return new Decision(Intent.LONG_FORM, false);

        var request = JSON.createObjectNode().put("message", text).put("awaitingDetails",
                references.get(run.context().userId(), run.context().actualSessionId()).isPresent());
        return Objects.requireNonNull(intentRouter.classify(request.toString()), "Story intent is missing");
    }

    private record Route(Intent intent, String condition, String name, String description,
                         BiFunction<Invocation, Decision, String> execute) {
        AgenticServices.AgenticScopeFunction<String> agent(AgentStepListener listener) {
            return AgenticServices.nonAiAgentBuilder(scope ->
                            execute.apply((Invocation) scope.readState(INVOCATION), (Decision) scope.readState("intent")))
                    .name(name).description(description).inputKey(String.class, "message")
                    .inputKey(Decision.class, "intent").inputKey(Invocation.class, INVOCATION)
                    .outputKey("reply").outputType(String.class).listener(listener).build();
        }
    }

    private record Invocation(String memoryId, String message, MemoryInvocationContext context, Consumer<String> chunks) {
        String trimmed() { return message.strip(); }
        String messageId() { return "chat-run-" + context.sourceExecutionId(); }

        String operationText() {
            String text = trimmed();
            int separator = text.indexOf(' ');
            if (!text.startsWith("/")) return text;
            return separator < 0 ? "" : text.substring(separator + 1).strip();
        }
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
        var run = new Invocation(memoryId, message, context, chunks);
        String reply = (String) workflow.invoke(Map.of("message", run.trimmed(), INVOCATION, run));
        return new ResultWithAgenticScope<>(null, reply);
    }

    private String skills(Invocation run) {
        JsonNode card = call(run.context().userId(), request(run.messageId(), "GetExtendedAgentCard").toString()).path("result");
        if (!card.path("skills").isArray()) throw new IllegalStateException("无法读取远端故事技能");
        var names = new java.util.ArrayList<String>();
        card.path("skills").forEach(skill -> names.add(skill.path("name").asText()));
        return "当前可以使用：" + String.join("、", names) + "。"
                + "你可以直接说「把刚才的结局改得温暖一点」「给上一个故事画一张插图」或「请核查这份稿件里的事实」。"
                + (names.contains("长篇小说模式") ? "也可以说明主题、风格和读者，直接请我写一部长篇小说。" : "");
    }

    private String illustrate(Invocation run, Decision decision) {
        String material = storyMaterial(run, decision);
        if (material.isBlank()) return "请提供要配图的故事，或先完成一篇故事。";
        return illustration.run(run.context(), run.memoryId(), material);
    }

    private String review(Invocation run, Decision decision, String skill) {
        String material = storyMaterial(run, decision);
        if (material.isBlank()) return "请提供需要审核的稿件或先完成一篇故事。";
        return review.run(material, skill);
    }

    private String storyMaterial(Invocation run, Decision decision) {
        String instruction = run.operationText();
        if (!decision.useLatestStory()) return instruction;
        long owner = run.context().userId();
        var previous = references.latestEdition(owner, run.context().actualSessionId()).orElse(null);
        if (previous == null) return "";
        var rpc = request(run.messageId(), "GetTask");
        rpc.putObject("params").put("id", previous.taskId());
        String story = call(owner, rpc.toString()).at("/result/artifacts/0/parts/0/text").asText("");
        if (story.isBlank() || instruction.isBlank()) return story;
        return "故事原文：\n" + story + "\n\n用户要求：\n" + instruction;
    }

    private String story(Invocation run, boolean revision, boolean longForm) {
        long owner = run.context().userId();
        String session = run.context().actualSessionId();
        String message = run.message();
        String messageId = run.messageId();
        String memoryId = run.memoryId();
        Consumer<String> chunks = run.chunks();
        var expected = references.get(owner, session);
        var edition = revision ? references.latestEdition(owner, session) : Optional.<Reference>empty();
        if (revision && edition.isEmpty()) return "请先完成一篇故事，再发起修订。";
        if (longForm && !longFormAccess.test(owner)) {
            return "当前账号无权使用长篇小说模式。";
        }
        var input = JSON.createObjectNode().put("messageId", messageId).put("role", "ROLE_USER");
        if (longForm) input.putObject("metadata").put("skillId", "write-novel");
        input.putArray("parts").addObject().put("text", revision || longForm ? run.operationText() : message);
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

        String prefix = revision ? "修订稿\n\n" : "";
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
        return reply;
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
