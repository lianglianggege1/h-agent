package com.h.backend.chat.infrastructure.ai.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.backend.chat.application.ImageGenerationService;
import com.h.backend.chat.domain.agent.AgentStepEventBridge;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.capabilities.memory.domain.MemoryInvocationContext;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.Set;

final class RemoteStoryIllustration {
    private static final Logger log = LoggerFactory.getLogger(RemoteStoryIllustration.class);
    private static final String AGENT = "story-illustrator";
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private final NacosA2AClientFactory registry;
    private final SecretKey signingKey;
    private final ImageGenerationService images;
    private final AgentStepEventBridge events;
    private final AgentObservability observability;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    RemoteStoryIllustration(NacosA2AClientFactory registry, SecretKey key, ImageGenerationService images,
                            AgentStepEventBridge events, AgentObservability observability) {
        this.registry = registry;
        this.signingKey = key;
        this.images = images;
        this.events = events;
        this.observability = observability;
    }

    String run(MemoryInvocationContext context, String memoryId, String text) {
        if (text.length() > 10000) return "请指定 10000 字以内的配图片段。";
        String run = "illustration-" + context.sourceExecutionId();
        var rpc = json.createObjectNode().put("jsonrpc", "2.0").put("id", run).put("method", "SendMessage");
        var message = rpc.putObject("params").putObject("message").put("role", "ROLE_USER").put("messageId", run);
        message.putArray("parts").addObject().put("text", text);
        var span = observability.span(ObservationSpec.of("remote_call a2a.story-illustrator",
                HObsKind.REMOTE_CALL, "a2a-client"), observability.currentContext());
        try {
            var request = HttpRequest.newBuilder(URI.create(registry.endpoint(AGENT)))
                    .timeout(Duration.ofMinutes(4)).header("Content-Type", "application/json")
                    .header("A2A-Version", "1.0").header("Authorization", "Bearer " + token(context.userId()))
                    .POST(HttpRequest.BodyPublishers.ofString(rpc.toString()));
            var headers = new HashMap<String, String>();
            observability.inject(span.context(), headers);
            headers.forEach(request::header);
            var response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (var body = response.body()) {
                if (response.statusCode() != 200) throw new IllegalStateException("Illustration request failed");
                int wireLimit = 14 * 1024 * 1024;
                byte[] wire = body.readNBytes(wireLimit + 1);
                if (wire.length > wireLimit) throw new IllegalStateException("Illustration response is too large");
                var task = json.readTree(wire).at("/result/task");
                var file = task.at("/artifacts/0/parts/0");
                String type = file.path("mediaType").asText();
                if (!"TASK_STATE_COMPLETED".equals(task.at("/status/state").asText())
                        || !Set.of("image/jpeg", "image/png", "image/webp").contains(type)) {
                    throw new IllegalStateException("Remote agent returned no usable image");
                }
                byte[] bytes = file.path("raw").binaryValue();
                if (bytes == null || bytes.length == 0 || bytes.length > MAX_IMAGE_BYTES) {
                    throw new IllegalStateException("Invalid illustration file");
                }
                var saved = images.attachRemoteIllustration(new ImageGenerationService.ImageGenerationCommand(
                        context.userId(), context.actualSessionId(), context.promptId(), "故事插画", "A2A"), bytes, type);
                events.emitTool(memoryId, new ChatStreamEvent("image", "", saved));
                span.attribute("a2a.task_id", task.path("id").asText());
                span.succeed();
                log.info("A2A illustration completed taskId={}", task.path("id").asText());
                return "插画已发到当前会话。";
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            span.cancel("interrupted");
            throw new IllegalStateException("Illustration request interrupted", interrupted);
        } catch (java.io.IOException | RuntimeException failure) {
            span.fail(failure);
            throw new IllegalStateException("Remote illustration failed", failure);
        }
    }

    private String token(long user) {
        var now = Instant.now();
        return Jwts.builder().issuer("h-agent").subject(Long.toString(user)).audience().add(AGENT).and()
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(120)))
                .signWith(signingKey, Jwts.SIG.HS256).compact();
    }
}
