package com.h.backend.chat.image;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.chat.config.ImageGenerationProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MiniMaxHttpImageClientTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldParseImageUrlsResponseAndDownloadImageBytes() throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        byte[] imageBytes = new byte[]{1, 2, 3, 4};
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/image_generation", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respondJson(exchange, """
                    {
                      "id": "03ff3cd0820949eb8a410056b5f21d38",
                      "data": {
                        "image_urls": ["%s/generated.png"]
                      },
                      "metadata": {
                        "failed_count": "0",
                        "success_count": "1"
                      },
                      "base_resp": {
                        "status_code": 0,
                        "status_msg": "success"
                      }
                    }
                    """.formatted(baseUrl()));
        });
        server.createContext("/generated.png", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "image/png");
            exchange.sendResponseHeaders(200, imageBytes.length);
            exchange.getResponseBody().write(imageBytes);
            exchange.close();
        });
        server.start();

        MiniMaxHttpImageClient client = new MiniMaxHttpImageClient(
                new ImageGenerationProperties(
                        null
                ),
                new ObjectMapper()
        );

        MiniMaxImageGenerationResult result = client.generate(new MiniMaxImageGenerationRequest(
                "image-01",
                "A white cat",
                "16:9",
                "url",
                1,
                true
        ));

        assertEquals("03ff3cd0820949eb8a410056b5f21d38", result.providerRequestId());
        assertEquals("image/png", result.mimeType());
        assertArrayEquals(imageBytes, result.imageBytes());
        assertTrue(requestBody.get().contains("\"response_format\":\"url\""));
        assertTrue(requestBody.get().contains("\"aspect_ratio\":\"16:9\""));
    }

    @Test
    void shouldFailWithProviderStatusMessageWhenMiniMaxReturnsError() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/image_generation", exchange -> respondJson(exchange, """
                {
                  "id": "failed-request",
                  "base_resp": {
                    "status_code": 1008,
                    "status_msg": "invalid api key"
                  }
                }
                """));
        server.start();

        MiniMaxHttpImageClient client = new MiniMaxHttpImageClient(
                new ImageGenerationProperties(
                        null
                ),
                new ObjectMapper()
        );

        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> client.generate(new MiniMaxImageGenerationRequest("image-01", "cat", "1:1", "url", 1, true))
        );

        assertTrue(error.getMessage().contains("invalid api key"));
        assertTrue(error.getMessage().contains("1008"));
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
