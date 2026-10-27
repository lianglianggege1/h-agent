package com.h.backend.generation.infrastructure.provider.minimax;

import static org.junit.jupiter.api.Assertions.*;

import com.h.backend.generation.application.port.out.VideoSubmissionUncertainException;
import com.h.backend.generation.domain.model.VideoV2Spec;
import com.h.backend.generation.infrastructure.config.GenerationProperties;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

class MiniMaxV2ClientTest {
    @Test
    void serverErrorsLeaveCreationUnknownWithoutRepeatingThePaidPost() throws Exception {
        var calls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v2/video_generation",
                x -> {
                    calls.incrementAndGet();
                    x.getRequestBody().readAllBytes();
                    x.sendResponseHeaders(502, -1);
                    x.close();
                });
        server.start();
        try {
            var p = new GenerationProperties();
            p.getMinimax().setApiKey("fixture");
            p.getMinimax().setVideoV2BaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new MiniMaxV2Client(p, new ObjectMapper());
            assertThrows(
                    VideoSubmissionUncertainException.class,
                    () ->
                            client.create(
                                    VideoV2Spec.defaults(
                                            "generation",
                                            "test",
                                            null,
                                            null,
                                            null,
                                            null,
                                            List.of()),
                                    List.of(Map.of("type", "text", "text", "test"))));
            assertEquals(1, calls.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void usesV2ContentAndReadsVideoAndTextResults() throws Exception {
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v2/video_generation",
                x -> {
                    var request = json.readTree(x.getRequestBody());
                    assertEquals("MiniMax-H3", request.path("model").asText());
                    assertEquals(15, request.path("duration").asInt());
                    assertFalse(request.has("prompt"));
                    assertEquals("text", request.path("content").get(0).path("type").asText());
                    byte[] b = "{\"task_id\":\"created\"}".getBytes(StandardCharsets.UTF_8);
                    x.sendResponseHeaders(200, b.length);
                    x.getResponseBody().write(b);
                    x.close();
                });
        server.createContext(
                "/v2/query/video_generation/created",
                x -> {
                    byte[] b =
                            "{\"task\":{\"status\":\"succeeded\",\"content\":{\"url\":\"https://example.test/video.mp4\"}}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    x.sendResponseHeaders(200, b.length);
                    x.getResponseBody().write(b);
                    x.close();
                });
        server.createContext(
                "/v1/query/video_generation",
                x -> {
                    byte[] bytes =
                            "{\"status\":\"preparing\",\"base_resp\":{\"status_code\":0}}"
                                    .getBytes(StandardCharsets.UTF_8);
                    x.sendResponseHeaders(200, bytes.length);
                    x.getResponseBody().write(bytes);
                    x.close();
                });
        server.start();
        try {
            var p = new GenerationProperties();
            p.getMinimax().setApiKey("fixture");
            p.getMinimax().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new MiniMaxV2Client(p, json);
            assertEquals(
                    "created",
                    client.create(
                            VideoV2Spec.defaults(
                                    "generation", "海边", null, 15, null, null, List.of()),
                            List.of(Map.of("type", "text", "text", "海边"))));
            var routing = new MiniMaxVideoClient(p, json);
            assertEquals("PREPARING", routing.query("legacy").status().name());
            assertEquals("SUCCESS", routing.query("v2:created").status().name());
            assertEquals(
                    "https://example.test/video.mp4", routing.retrieve("v2:created").downloadUrl());
            assertEquals(
                    "https://example.test/video.mp4",
                    client.query("created").path("content").path("url").asText());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void implementsEnhancementRegenerationListAndDeleteWithoutLeakingForeignTasks()
            throws Exception {
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v2",
                x -> {
                    String path = x.getRequestURI().getPath();
                    String response;
                    if (x.getRequestMethod().equals("POST")) {
                        var body = json.readTree(x.getRequestBody());
                        if (path.equals("/v2/h3_context_ir")) {
                            assertFalse(body.has("resolution"));
                            assertEquals(6, body.path("duration").asInt());
                        } else {
                            assertEquals("/v2/video_regeneration", path);
                            assertEquals("source", body.path("source_task_id").asText());
                            assertEquals("2K", body.path("resolution").asText());
                            assertFalse(body.has("content"));
                        }
                        response = "{\"task_id\":\"new-task\"}";
                    } else if (x.getRequestMethod().equals("DELETE"))
                        response = "{\"action\":\"deleted\",\"status\":\"succeeded\"}";
                    else if (path.equals("/v2/query/video_generation")) {
                        assertTrue(x.getRequestURI().getQuery().contains("filter.task_ids=own"));
                        response =
                                "{\"items\":[{\"task_id\":\"own\",\"status\":\"queued\"},{\"task_id\":\"foreign\",\"status\":\"running\"}],\"total\":2}";
                    } else
                        response =
                                "{\"task\":{\"status\":\"succeeded\",\"task_type\":\"h3_context_ir\",\"content\":{\"prompt\":\"enhanced\"}}}";
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    x.sendResponseHeaders(200, bytes.length);
                    x.getResponseBody().write(bytes);
                    x.close();
                });
        server.start();
        try {
            var p = new GenerationProperties();
            p.getMinimax().setApiKey("fixture");
            p.getMinimax().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            var client = new MiniMaxV2Client(p, json);
            client.create(
                    VideoV2Spec.defaults("h3_context_ir", "原始", null, 6, null, null, List.of()),
                    List.of(Map.of("type", "text", "text", "原始")));
            client.create(
                    new VideoV2Spec(
                            "regeneration",
                            "最终提示词",
                            "MiniMax-H3",
                            6,
                            "2K",
                            "16:9",
                            List.of(),
                            "source"),
                    List.of());
            assertEquals("enhanced", client.inspect("ir").prompt());
            assertEquals(
                    List.of("own"),
                    client.listOwned(List.of("own"), 1, 20, null, null, null).stream()
                            .map(t -> t.taskId())
                            .toList());
            assertEquals("deleted", client.remove("own").action());
        } finally {
            server.stop(0);
        }
    }
}
