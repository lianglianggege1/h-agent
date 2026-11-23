package com.h.otheragents.a2a.document;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.lifecycle.ExecutionContextSnapshot;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.otheragents.observability.A2AObservabilityWebFilter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.h.capabilities.common.exception.BusinessException;
import com.h.capabilities.document.domain.DocumentTask;
import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import jakarta.annotation.PreDestroy;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.a2aproject.sdk.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.crypto.SecretKey;
import org.slf4j.LoggerFactory;

@RestController
@ConditionalOnProperty(prefix = "other-agents.a2a.documents", name = "enabled", havingValue = "true")
public class RemoteDocumentA2AController {
    public static final String ID = "document-reader";
    private static final String ROOT = "/a2a/agents/" + ID;
    private static final Set<String> RUNNABLE = Set.of("PARSING", "QUEUED", "OCR_RUNNING", "VERIFYING");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(RemoteDocumentA2AController.class);
    private static final String CALLBACK_AUDIENCE = "h-agent-document-notification";
    private static final Set<String> NOTIFIABLE = Set.of("TASK_STATE_COMPLETED", "TASK_STATE_FAILED",
            "TASK_STATE_CANCELED", "TASK_STATE_INPUT_REQUIRED");
    private final RemoteDocumentWork documents;
    private final AgentObservability observability;
    private final OtherAgentsA2AProperties properties;
    private final io.jsonwebtoken.JwtParser authorization;
    private final SecretKey signingKey;
    private final String callback;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final java.util.concurrent.ScheduledExecutorService worker = Executors.newScheduledThreadPool(2,
            Thread.ofPlatform().name("a2a-document-worker").daemon(true).factory());
    private boolean queueReady;
    private boolean waitingForMigration;

    RemoteDocumentA2AController(RemoteDocumentWork documents, OtherAgentsA2AProperties properties,
            AgentObservability observability,
            @Value("${other-agents.a2a.story.signing-key}") String secret,
            @Value("${other-agents.a2a.documents.callback-url:}") String callback) {
        this.documents = documents;
        this.observability = observability;
        this.properties = properties;
        this.callback = callback;
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.authorization = Jwts.parser().verifyWith(signingKey)
                .requireIssuer("h-agent").requireAudience(ID).build();
    }

    @EventListener(ApplicationReadyEvent.class)
    void start() { worker.scheduleWithFixedDelay(this::work, 1, 1, TimeUnit.SECONDS); }

    @PreDestroy
    void stop() { worker.shutdownNow(); }

    public AgentCard card() {
        String address = properties.agentUrl(ID);
        return AgentCard.builder().name(ID).description("解析 PDF，执行 OCR 与质量复核")
                .version(properties.getVersion()).url(address)
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", address)))
                .capabilities(AgentCapabilities.builder().pushNotifications(!callback.isBlank()).build())
                .securitySchemes(Map.of("bearer", new HTTPAuthSecurityScheme("JWT", "Bearer", null)))
                .securityRequirements(List.of(new SecurityRequirement(Map.of("bearer", List.of()))))
                .defaultInputModes(List.of("application/pdf")).defaultOutputModes(List.of("application/json"))
                .skills(List.of(AgentSkill.builder().id("parse-pdf").name("PDF 解析与 OCR")
                        .description("返回页码、文档结构与质量信息").tags(List.of("pdf", "ocr"))
                        .inputModes(List.of("application/pdf")).outputModes(List.of("application/json")).build()))
                .build();
    }

    @GetMapping(value = ROOT + "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String publicCard() throws Exception { return JSON.writeValueAsString(card()); }

    @PostMapping(value = ROOT, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> request(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                                               String header, @RequestBody String body,
                                               @RequestAttribute(name = A2AObservabilityWebFilter.EXCHANGE_ATTRIBUTE, required = false)
                                               ObservationContext observation) {
        final long owner;
        final String allowedTask;
        try {
            if (header == null || !header.startsWith("Bearer ")) throw new IllegalArgumentException();
            var claims = authorization.parseSignedClaims(header.substring(7)).getPayload();
            if (claims.getExpiration() == null) throw new IllegalArgumentException();
            owner = Long.parseLong(claims.getSubject());
            allowedTask = claims.get("task_id", String.class);
            if (allowedTask == null) throw new IllegalArgumentException();
        } catch (JwtException | IllegalArgumentException failure) {
            return Mono.just(ResponseEntity.status(HttpStatus.UNAUTHORIZED).build());
        }
        return Mono.fromCallable(() -> {
            try (var scope = ExecutionContextSnapshot.forObservation(observation).scope()) {
                return ResponseEntity.ok(handle(owner, allowedTask, body));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private String handle(long owner, String allowedTask, String raw) {
        var response = JSON.createObjectNode().put("jsonrpc", "2.0");
        try {
            JsonNode input = JSON.readTree(raw);
            response.set("id", input.path("id").isMissingNode() ? JSON.nullNode() : input.path("id"));
            JsonNode params = input.path("params");
            String method = input.path("method").asText();
            String requested = "SendMessage".equals(method) ? params.at("/message/taskId").asText() : params.path("id").asText();
            if (!allowedTask.equals(requested)) return error(response, -32001, "Document task not accessible");
            switch (method) {
                case "GetTask" -> {
                    var document = documents.find(owner, requested);
                    var snapshot = task(document);
                    boolean admitted = documents.jdbc().sql("""
                            SELECT EXISTS(SELECT 1 FROM a2a_document_steps WHERE user_id=? AND task_id=? AND revision=?)
                            """).params(owner, requested, document.getRevision()).query(Boolean.class).single();
                    snapshot.withObject("/metadata").put("stepAccepted", admitted);
                    response.set("result", snapshot);
                }
                case "CancelTask" -> {
                    var document = documents.find(owner, params.path("id").asText());
                    documents.workflow().action(owner, document.getSessionId(), document.getId(), document.getRevision(),
                            "CANCEL", "", false);
                    response.set("result", task(documents.find(owner, document.getId())));
                }
                case "SendMessage" -> {
                    JsonNode message = params.path("message");
                    var document = documents.find(owner, message.path("taskId").asText());
                    JsonNode file = message.at("/parts/0");
                    if (!"application/pdf".equals(file.path("mediaType").asText())
                            || !("urn:h-agent:chat-resource:" + document.getResourceId()).equals(file.path("url").asText())
                            || !document.getSessionId().equals(message.path("contextId").asText())
                            || document.getActor() != null) {
                        return error(response, -32602, "Invalid PDF reference");
                    }
                    long revision = message.at("/metadata/revision").asLong(-1);
                    if (document.getRevision() == revision && RUNNABLE.contains(document.getStatus())) {
                        JsonNode push = params.at("/configuration/taskPushNotificationConfig");
                        boolean notify = !callback.isBlank() && callback.equals(push.path("url").asText())
                                && "Bearer".equalsIgnoreCase(push.at("/authentication/scheme").asText());
                        documents.jdbc().sql("""
                                INSERT INTO a2a_document_steps(task_id, revision, user_id, callback_requested)
                                VALUES(?,?,?,?) ON CONFLICT (task_id, revision) DO NOTHING
                                """).params(document.getId(), revision, owner, notify).update();
                    }
                    response.putObject("result").set("task", task(documents.find(owner, document.getId())));
                }
                default -> { return error(response, -32601, "Method not found"); }
            }
            return response.toString();
        } catch (BusinessException failure) {
            return error(response, -32001, "Document task not accessible");
        } catch (Exception failure) {
            return error(response, -32603, "Document operation failed");
        }
    }

    void work() {
        try {
            if (!queueReady) {
                // The backend owns this migration and may start after other-agents.
                queueReady = documents.jdbc().sql("SELECT to_regclass('a2a_document_steps') IS NOT NULL")
                        .query(Boolean.class).single();
                if (!queueReady) {
                    if (!waitingForMigration) LOG.info("Document A2A queue waiting for backend database migration");
                    waitingForMigration = true;
                    return;
                }
                LOG.info("Document A2A queue ready");
            }
            String lease = UUID.randomUUID().toString();
            var pending = documents.jdbc().sql("""
                    WITH picked AS (
                        SELECT task_id, revision FROM a2a_document_steps
                        WHERE state IN ('PENDING','NOTIFY') AND available_at <= NOW()
                          AND (lease_until IS NULL OR lease_until < NOW())
                        ORDER BY available_at, task_id, revision FOR UPDATE SKIP LOCKED LIMIT 1
                    )
                    UPDATE a2a_document_steps s SET lease_token=?, lease_until=NOW()+INTERVAL '45 seconds'
                    FROM picked JOIN chat_document_tasks d ON d.id=picked.task_id
                    WHERE s.task_id=picked.task_id AND s.revision=picked.revision
                    RETURNING s.task_id, s.revision, s.user_id, s.callback_requested, s.notification_json, s.attempts,
                              d.state_json::jsonb->>'traceId'
                    """).param(lease).query((row, index) -> new Step(row.getString(1), row.getLong(2),
                            row.getLong(3), row.getBoolean(4), row.getString(5), row.getInt(6), row.getString(7))).optional();
            if (pending.isEmpty()) return;
            Step step = pending.get();
            var parent = ObservationContext.recoverTraceId(step.traceId() == null
                    ? step.id().replace("-", "") : step.traceId());
            var snapshot = ExecutionContextSnapshot.forObservation(parent);
            try (var scope = snapshot.scope()) {
                work(step, lease, snapshot);
            }
        } catch (RuntimeException failure) {
            LOG.warn("Document A2A queue unavailable", failure);
        }
    }

    private void work(Step step, String lease, ExecutionContextSnapshot snapshot) {
        var heartbeat = worker.scheduleAtFixedRate(() -> {
            try (var scope = snapshot.scope()) {
                renew(step, lease);
            }
        }, 10, 10, TimeUnit.SECONDS);
        try {
            if (step.notification() == null) process(step, lease);
            else {
                sendNotification(step);
                settle(step, lease, "DONE", null, 0);
            }
        } catch (BusinessException removed) {
            settle(step, lease, "DONE", null, 0);
        } catch (InterruptedException stopped) {
            settle(step, lease, step.notification() == null ? "PENDING" : "NOTIFY", step.notification(), 0);
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            LOG.warn("Document A2A step failed task={} revision={}", step.id(), step.revision(), failure);
            String state = step.notification() == null ? "PENDING" : "NOTIFY";
            settle(step, lease, state, step.notification(), Math.min(300, 5 * (1 + step.attempts())));
        } finally {
            heartbeat.cancel(false);
        }
    }

    private void renew(Step step, String lease) {
        try {
            documents.jdbc().sql("""
                    UPDATE a2a_document_steps SET lease_until=NOW()+INTERVAL '45 seconds'
                    WHERE task_id=? AND revision=? AND lease_token=?
                    """).params(step.id(), step.revision(), lease).update();
        } catch (RuntimeException failure) {
            LOG.warn("Document A2A lease renewal unavailable task={} revision={}", step.id(), step.revision(), failure);
        }
    }

    private void process(Step step, String lease) throws Exception {
        var document = documents.find(step.owner(), step.id());
        if (document.getRevision() == step.revision() && RUNNABLE.contains(document.getStatus())) {
            documents.workflow().process(document);
        }
        String notification = null;
        if (step.callbackRequested()) {
            var updated = documents.find(step.owner(), step.id());
            var snapshot = task(updated);
            if (NOTIFIABLE.contains(snapshot.at("/status/state").asText())) notification = notification(updated, snapshot);
        }
        settle(step, lease, notification == null ? "DONE" : "NOTIFY", notification, 0);
    }

    private void settle(Step step, String lease, String state, String notification, int delay) {
        documents.jdbc().sql("""
                UPDATE a2a_document_steps SET state=?, notification_json=?,
                    available_at=NOW()+CAST(? AS INTEGER)*INTERVAL '1 second', attempts=attempts+1,
                    lease_token=NULL, lease_until=NULL WHERE task_id=? AND revision=? AND lease_token=?
                """).params(state, notification, delay, step.id(), step.revision(), lease).update();
    }

    private String notification(DocumentTask document, ObjectNode snapshot) {
        var event = JSON.createObjectNode();
        var update = event.putObject("statusUpdate").put("taskId", document.getId())
                .put("contextId", document.getSessionId());
        update.set("status", snapshot.get("status"));
        update.putObject("metadata").put("revision", document.getRevision());
        return event.toString();
    }

    private void sendNotification(Step step) throws java.io.IOException, InterruptedException {
        if (callback.isBlank()) throw new IllegalStateException("Document callback unavailable");
        var now = Instant.now();
        String token = Jwts.builder().issuer("h-agent").subject(Long.toString(step.owner()))
                .audience().add(CALLBACK_AUDIENCE).and().claim("task_id", step.id())
                .issuedAt(Date.from(now)).expiration(Date.from(now.plusSeconds(60)))
                .signWith(signingKey, Jwts.SIG.HS256).compact();
        var span = observability.span(ObservationSpec.of("remote_call a2a.document-callback", HObsKind.REMOTE_CALL,
                "a2a-client", Map.of("a2a.task_id", step.id())), observability.currentContext());
        try {
            var request = HttpRequest.newBuilder(URI.create(callback)).timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/a2a+json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(step.notification()));
            var headers = new java.util.HashMap<String, String>();
            observability.inject(span.context(), headers);
            headers.forEach(request::header);
            var reply = http.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (reply.statusCode() < 200 || reply.statusCode() >= 300) throw new java.io.IOException("Document callback rejected");
            span.succeed();
            LOG.info("A2A document notification delivered taskId={} revision={}", step.id(), step.revision());
        } catch (InterruptedException failure) {
            span.cancel("interrupted");
            throw failure;
        } catch (java.io.IOException | RuntimeException failure) {
            span.fail(failure);
            throw failure;
        }
    }

    private record Step(String id, long revision, long owner, boolean callbackRequested, String notification, int attempts, String traceId) {}

    private ObjectNode task(DocumentTask document) throws Exception {
        String state = switch (document.getStatus()) {
            case "PARSING" -> "SUBMITTED";
            case "WAITING_CONFIRMATION", "VERIFICATION_FAILED", "CLOUD_UNAVAILABLE" -> "INPUT_REQUIRED";
            case "CANCELLED", "SUPERSEDED" -> "CANCELED";
            case "FAILED", "BUDGET_EXHAUSTED", "UNSUPPORTED" -> "FAILED";
            default -> document.released() ? "COMPLETED" : "WORKING";
        };
        var task = JSON.createObjectNode().put("id", document.getId()).put("contextId", document.getSessionId());
        task.putObject("metadata").put("revision", document.getRevision());
        task.putObject("status").put("state", "TASK_STATE_" + state);
        if ("COMPLETED".equals(state)) {
            var data = (ObjectNode) JSON.readTree(documents.workflow().toolView(document, 0));
            var pages = data.putArray("pages");
            for (var page : document.getPages()) {
                pages.addObject().put("number", page.getNumber()).put("quality", page.getQuality());
            }
            task.putArray("artifacts").addObject().put("artifactId", "document-" + document.getId())
                    .putArray("parts").addObject().set("data", data);
        }
        return task;
    }

    private static String error(ObjectNode response, int code, String text) {
        response.putObject("error").put("code", code).put("message", text);
        return response.toString();
    }
}
