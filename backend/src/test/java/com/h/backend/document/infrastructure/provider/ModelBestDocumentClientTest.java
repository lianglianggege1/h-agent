package com.h.backend.document.infrastructure.provider;

import static org.junit.jupiter.api.Assertions.*;

import com.h.backend.common.testsupport.CapturingLogAppender;
import com.h.backend.document.domain.DocumentTask;
import com.h.backend.document.infrastructure.config.DocumentProperties;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tools.jackson.databind.ObjectMapper;

import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

class ModelBestDocumentClientTest {
    @Test
    void hostedFileJobsAndJevUseDocumentedContracts() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var requests = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/",
                exchange -> {
                    String path = exchange.getRequestURI().getPath();
                    requests.add(path);
                    assertEquals(
                            "Bearer test-only",
                            exchange.getRequestHeaders().getFirst("Authorization"));
                    byte[] body = exchange.getRequestBody().readAllBytes();
                    String response;
                    if (path.equals("/v1/files")) {
                        assertTrue(
                                exchange.getRequestHeaders()
                                        .getFirst("Content-Type")
                                        .startsWith("multipart/form-data; boundary="));
                        assertTrue(
                                new String(body, StandardCharsets.ISO_8859_1)
                                        .contains("name=\"file\""));
                        response = "{\"file_id\":\"file1\"}";
                    } else if (path.equals("/v1/document-parse/jobs")) {
                        var input = json.readTree(body);
                        assertEquals("file1", input.path("file_id").asText());
                        assertFalse(input.path("upright").asBoolean(true));
                        assertTrue(input.path("recognize_seal").asBoolean());
                        assertEquals(
                                "stable-key",
                                exchange.getRequestHeaders().getFirst("Idempotency-Key"));
                        response = "{\"id\":\"ocr_test\",\"status\":\"Running\"}";
                    } else if (path.equals("/v1/document-parse/jobs/ocr_test"))
                        response = "{\"status\":\"Running\"}";
                    else {
                        var input = json.readTree(body);
                        assertEquals("MiniCPM-OCR-Jev", input.path("model").asText());
                        assertEquals("score", input.path("minicpm_ocr_jev").path("mode").asText());
                        assertEquals("text", input.path("minicpm_ocr_jev").path("category").asText());
                        assertFalse(input.path("minicpm_ocr_jev").has("items"));
                        assertFalse(input.path("minicpm_ocr_jev").has("threshold"));
                        var content = input.path("messages").path(0).path("content");
                        assertEquals("原图对应文字", content.path(1).path("text").asText());
                        assertTrue(content.path(0).path("image_url").path("url")
                                .asText().startsWith("data:image/png;base64,"));
                        String scored =
                                json.writeValueAsString(
                                        Map.of(
                                                "results",
                                                List.of(
                                                        Map.of(
                                                                "case_id",
                                                                "0",
                                                                "verdict",
                                                                "scored",
                                                                "prob_yes",
                                                                .98))));
                        response =
                                json.writeValueAsString(
                                        Map.of(
                                                "choices",
                                                List.of(
                                                        Map.of(
                                                                "message",
                                                                Map.of("content", scored)))));
                    }
                    byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
        try {
            var config = new DocumentProperties();
            config.setModelbestApiKey("test-only");
            config.setModelbestBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            var client = new ModelBestDocumentClient(config, json);
            var image = new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB);
            assertEquals("file1", client.upload(image));
            assertEquals("ocr_test", client.submit("file1", "stable-key"));
            assertEquals("Running", client.query("ocr_test").path("status").asText());
            var block = new DocumentTask.Block();
            block.setId("block1");
            block.setText("原图对应文字");
            assertEquals(.98, client.verify(image, block).path("prob_yes").asDouble());
            assertEquals(4, requests.size());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"invalid_image", "content_policy_violation"})
    void rejectedReviewLogsSafeDiagnosticsAndKeepsContentOutOfTaskDetail(String errorCode) throws Exception {
        var json = new ObjectMapper();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            var input = json.readTree(exchange.getRequestBody().readAllBytes());
            var content = input.path("messages").path(0).path("content");
            String message = "Invalid image: " + content.path(0).path("image_url").path("url").asText()
                    + " text=" + content.path(1).path("text").asText()
                    + " credential=test-only";
            byte[] response = json.writeValueAsBytes(Map.of("error", Map.of(
                    "code", errorCode, "message", message,
                    "request", input)));
            exchange.getResponseHeaders().set("x-request-id", "provider-probe-123");
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        try (var logs = CapturingLogAppender.attach(ModelBestDocumentClient.class)) {
            var config = new DocumentProperties();
            config.setModelbestApiKey("test-only");
            config.setModelbestBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            var block = new DocumentTask.Block();
            block.setId("p1-b1");
            block.setText("PRIVATE DOCUMENT CONTENT");
            var error = assertThrows(ModelBestDocumentClient.ProviderFailure.class,
                    () -> new ModelBestDocumentClient(config, json).verify(
                            new BufferedImage(20, 30, BufferedImage.TYPE_INT_RGB), block));
            assertFalse(error.isRetryable());
            assertTrue(error.getMessage().contains("400"));
            assertTrue(error.getMessage().contains("Jev"));
            if ("content_policy_violation".equals(errorCode)) {
                assertTrue(error.getMessage().contains("供应商内容审核拒绝"));
                assertTrue(error.getMessage().contains(errorCode));
                assertTrue(error.getMessage().contains("provider-probe-123"));
            }
            assertFalse(error.getMessage().contains("PRIVATE"));
            String output = logs.appender().events().stream()
                    .map(event -> event.getMessage().getFormattedMessage())
                    .collect(java.util.stream.Collectors.joining("\n"));
            assertTrue(logs.appender().events().stream().anyMatch(event ->
                    event.getThrown() == error
                            && event.getLevel() == org.apache.logging.log4j.Level.ERROR));
            assertTrue(output.contains(errorCode));
            assertTrue(output.contains("provider-probe-123"));
            assertTrue(output.contains("blockId=p1-b1"));
            assertTrue(output.contains("imageWidth=20"));
            assertTrue(output.contains("requestBytes="));
            assertFalse(output.contains("PRIVATE DOCUMENT CONTENT"));
            assertFalse(output.contains("test-only"));
            assertFalse(output.contains("data:image"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void responseBodyMustRespectRequestDeadline() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/v1/document-parse/jobs/slow",
                exchange -> {
                    exchange.sendResponseHeaders(200, 0);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                    try {
                        Thread.sleep(3000);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                    exchange.close();
                });
        server.start();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var config = new DocumentProperties();
            config.setModelbestApiKey("test-only");
            config.setRequestTimeoutSeconds(1);
            config.setModelbestBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            var client = new ModelBestDocumentClient(config, new ObjectMapper());
            var call = executor.submit(() -> client.query("slow"));
            try {
                var failure =
                        assertThrows(
                                java.util.concurrent.ExecutionException.class,
                                () -> call.get(1800, java.util.concurrent.TimeUnit.MILLISECONDS));
                assertInstanceOf(ModelBestDocumentClient.ProviderFailure.class, failure.getCause());
                assertTrue(
                        ((ModelBestDocumentClient.ProviderFailure) failure.getCause())
                                .isRetryable());
                assertInstanceOf(java.net.http.HttpTimeoutException.class,
                        failure.getCause().getCause());
            } finally {
                call.cancel(true);
            }
        } finally {
            server.stop(0);
        }
    }
}
