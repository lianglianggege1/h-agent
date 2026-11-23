package com.h.backend.document.infrastructure.a2a;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.h.backend.chat.infrastructure.ai.a2a.NacosA2AClientFactory;
import com.h.capabilities.document.domain.DocumentTask;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Locale;

@Component
@ConditionalOnProperty(prefix = "agents.a2a.other-agents", name = {"enabled", "documents.enabled"}, havingValue = "true")
public class A2ADocumentDispatch {
    public static final String READER = "document-reader";
    public static final String CALLBACK_AUDIENCE = "h-agent-document-notification";
    private final NacosA2AClientFactory registry;
    private final SecretKey key;
    private final String callback;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public A2ADocumentDispatch(NacosA2AClientFactory registry,
            @Value("${agents.a2a.other-agents.story.signing-key}") String secret,
            @Value("${agents.a2a.other-agents.documents.callback-url:}") String callback) {
        this.registry = registry;
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.callback = callback;
    }

    public boolean accepts(DocumentTask task) {
        return task.getActor() == null && task.getResourceId() != null && task.getFileName() != null
                && ("PARSE".equals(task.getKind()) || "REVIEW".equals(task.getKind()))
                && task.getFileName().toLowerCase(Locale.ROOT).endsWith(".pdf");
    }

    public void process(DocumentTask task) {
        String id = task.getId() + "-r" + task.getRevision();
        var request = json.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", "SendMessage");
        var params = request.putObject("params");
        var message = params.putObject("message").put("messageId", id).put("role", "ROLE_USER")
                .put("taskId", task.getId()).put("contextId", task.getSessionId());
        message.putObject("metadata").put("revision", task.getRevision());
        message.putArray("parts").addObject().put("url", "urn:h-agent:chat-resource:" + task.getResourceId())
                .put("filename", task.getFileName()).put("mediaType", "application/pdf");
        var config = params.putObject("configuration").put("returnImmediately", true);
        if (!callback.isBlank()) {
            config.putObject("taskPushNotificationConfig").put("url", callback).putObject("authentication")
                    .put("scheme", "Bearer").put("credentials", token(task, CALLBACK_AUDIENCE, 300));
        }
        try {
            if (!task.getId().equals(call(task, request.toString()).at("/result/task/id").asText())) {
                throw new IllegalStateException("A2A document step did not return the expected task");
            }
        } catch (IllegalStateException uncertain) {
            if (Thread.currentThread().isInterrupted()) throw uncertain;
            var query = json.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", "GetTask");
            query.putObject("params").put("id", task.getId());
            try {
                var current = call(task, query.toString()).path("result");
                if (task.getId().equals(current.path("id").asText())
                        && (current.at("/metadata/revision").asLong(-1) > task.getRevision()
                        || current.at("/metadata/stepAccepted").asBoolean())) return;
            } catch (IllegalStateException queryFailed) {
                uncertain.addSuppressed(queryFailed);
            }
            throw uncertain;
        }
    }

    public void cancel(DocumentTask task) {
        var request = json.createObjectNode().put("jsonrpc", "2.0").put("id", task.getId())
                .put("method", "CancelTask");
        request.putObject("params").put("id", task.getId());
        call(task, request.toString());
    }

    private JsonNode call(DocumentTask task, String body) {
        try {
            var response = http.send(HttpRequest.newBuilder(URI.create(registry.endpoint(READER)))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json").header("A2A-Version", "1.0")
                    .header("Authorization", "Bearer " + token(task, READER, 30))
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                    HttpResponse.BodyHandlers.ofString());
            var result = json.readTree(response.body());
            if (response.statusCode() != 200 || result == null || result.has("error")) {
                throw new IllegalStateException("A2A document step failed");
            }
            return result;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("A2A document step interrupted", failure);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("A2A document agent temporarily unavailable", failure);
        }
    }

    private String token(DocumentTask task, String audience, long ttl) {
        var now = Instant.now();
        return Jwts.builder().issuer("h-agent").subject(task.getUserId().toString())
                .audience().add(audience).and().claim("task_id", task.getId())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(ttl)))
                .signWith(key, Jwts.SIG.HS256).compact();
    }
}
