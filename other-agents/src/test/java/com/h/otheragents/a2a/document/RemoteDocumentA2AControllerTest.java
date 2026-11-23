package com.h.otheragents.a2a.document;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.agent.observability.NoopAgentObservability;
import com.h.capabilities.document.application.DocumentWorkflow;
import com.h.capabilities.document.domain.DocumentTask;
import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.sun.net.httpserver.HttpServer;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RemoteDocumentA2AControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SECRET = "test-only-shared-a2a-document-signing-key";

    @Test
    @EnabledIfEnvironmentVariable(named = "A2A_STORY_TEST_DB_URL", matches = ".+")
    void admitsOnceAndRetriesPushAfterWorkerRestart() throws Exception {
        String database = System.getenv("A2A_STORY_TEST_DB_URL");
        String schema = "document_a2a_test_" + UUID.randomUUID().toString().replace("-", "");
        var admin = JdbcClient.create(new DriverManagerDataSource(database, "postgres", ""));
        admin.sql("CREATE SCHEMA " + schema).update();
        var remote = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try {
            String separator = database.contains("?") ? "&" : "?";
            var dataSource = new DriverManagerDataSource(database + separator + "currentSchema=" + schema, "postgres", "");
            var jdbc = JdbcClient.create(dataSource);
            jdbc.sql("CREATE TABLE chat_document_tasks(id VARCHAR(64) PRIMARY KEY, user_id BIGINT, session_id VARCHAR(64), state_json TEXT)").update();
            jdbc.sql(Files.readString(Path.of("../backend/src/main/resources/db/migration/V20261120_08__document_a2a_steps.sql"))).update();
            String traceId = "4bf92f3577b34da6a3ce929d0e0e4736";
            jdbc.sql("INSERT INTO chat_document_tasks VALUES('task',42,'session',?)")
                    .param(JSON.createObjectNode().put("traceId", traceId).toString()).update();
            var attempts = new AtomicInteger();
            var payload = new AtomicReference<String>();
            var auth = new AtomicReference<String>();
            var traceparent = new AtomicReference<String>();
            remote.createContext("/notify", request -> {
                payload.set(new String(request.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                auth.set(request.getRequestHeaders().getFirst("Authorization"));
                traceparent.set(request.getRequestHeaders().getFirst("traceparent"));
                request.sendResponseHeaders(attempts.incrementAndGet() == 1 ? 503 : 204, -1);
                request.close();
            });
            remote.start();
            String callback = "http://127.0.0.1:" + remote.getAddress().getPort() + "/notify";
            var task = new DocumentTask();
            task.setId("task");
            task.setUserId(42L);
            task.setSessionId("session");
            task.setResourceId("file");
            task.setStatus("PARSING");
            var workflow = mock(DocumentWorkflow.class);
            when(workflow.require(42L, "session", "task")).thenReturn(task);
            when(workflow.toolView(task, 0)).thenReturn("{\"content\":\"parsed\"}");
            doAnswer(call -> {
                task.setStatus("GOOD");
                task.setRevision(1);
                return null;
            }).when(workflow).process(task);
            var work = new RemoteDocumentWork(workflow, jdbc, null);
            var observability = NoopAgentObservability.getInstance();
            var first = new RemoteDocumentA2AController(work, new OtherAgentsA2AProperties(), observability, SECRET, callback);
            var replacement = new RemoteDocumentA2AController(work, new OtherAgentsA2AProperties(), observability, SECRET, callback);
            try {
                var key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));
                String token = "Bearer " + Jwts.builder().issuer("h-agent").subject("42")
                        .audience().add("document-reader").and().claim("task_id", "task")
                        .expiration(Date.from(Instant.now().plusSeconds(60))).signWith(key, Jwts.SIG.HS256).compact();
                String send = """
                        {"jsonrpc":"2.0","id":"send","method":"SendMessage","params":{
                          "message":{"taskId":"task","contextId":"session","metadata":{"revision":0},
                            "parts":[{"mediaType":"application/pdf","url":"urn:h-agent:chat-resource:file"}]},
                          "configuration":{"returnImmediately":true,"taskPushNotificationConfig":{
                            "url":"%s","authentication":{"scheme":"Bearer"}}}}}
                        """.formatted(callback);
                assertEquals("TASK_STATE_SUBMITTED", JSON.readTree(call(first, token, send)).at("/result/task/status/state").asText());
                call(first, token, send);
                assertEquals(1, jdbc.sql("SELECT COUNT(*) FROM a2a_document_steps").query(Integer.class).single());
                first.work();
                verify(workflow, times(1)).process(task);
                first.work();
                assertEquals(1, attempts.get());
                jdbc.sql("UPDATE a2a_document_steps SET available_at=NOW()").update();
                replacement.work();
                assertEquals(2, attempts.get());
                assertEquals(traceId, traceparent.get().split("-")[1]);
                assertEquals("DONE", jdbc.sql("SELECT state FROM a2a_document_steps").query(String.class).single());
                assertEquals("TASK_STATE_COMPLETED", JSON.readTree(payload.get()).at("/statusUpdate/status/state").asText());
                assertEquals("task", Jwts.parser().verifyWith(key).requireAudience("h-agent-document-notification")
                        .build().parseSignedClaims(auth.get().substring(7)).getPayload().get("task_id"));
                String get = "{\"jsonrpc\":\"2.0\",\"id\":\"get\",\"method\":\"GetTask\",\"params\":{\"id\":\"task\"}}";
                assertEquals("parsed", JSON.readTree(call(replacement, token, get)).at("/result/artifacts/0/parts/0/data/content").asText());
                verify(workflow, times(1)).process(task);
            } finally {
                first.stop();
                replacement.stop();
            }
        } finally {
            remote.stop(0);
            admin.sql("DROP SCHEMA " + schema + " CASCADE").update();
        }
    }

    private static String call(RemoteDocumentA2AController controller, String token, String request) {
        var result = controller.request(token, request, null).block(Duration.ofSeconds(5));
        assertNotNull(result);
        return result.getBody();
    }
}
