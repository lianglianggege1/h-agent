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
import static org.junit.jupiter.api.Assertions.assertThrows;
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
                        new ImageGenerationProperties.MiniMax(baseUrl(), "test-key", "image-01", "16:9", true),
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
                true,
                null
        ));

        assertEquals("03ff3cd0820949eb8a410056b5f21d38", result.providerRequestId());
        assertEquals("image/png", result.mimeType());
        assertArrayEquals(imageBytes, result.imageBytes());
        assertTrue(requestBody.get().contains("\"response_format\":\"url\""));
        assertTrue(requestBody.get().contains("\"aspect_ratio\":\"16:9\""));
    }

    @Test
    void shouldDownloadAllImageUrlsAndInferJpegMimeTypeFromSignedUrlPath() throws Exception {
        byte[] firstImageBytes = new byte[]{1, 2, 3};
        byte[] secondImageBytes = new byte[]{4, 5, 6};
        byte[] thirdImageBytes = new byte[]{7, 8, 9};
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/image_generation", exchange -> respondJson(exchange, """
                {
                  "id": "0671753b929f94c253619b309b2b9f76",
                  "data": {
                    "image_urls": [
                      "%s/generated-one.jpeg?Expires=1780716503&Signature=one",
                      "%s/generated-two.jpeg?Expires=1780716503&Signature=two",
                      "%s/generated-three.jpeg?Expires=1780716503&Signature=three"
                    ]
                  },
                  "metadata": {
                    "failed_count": "0",
                    "success_count": "3"
                  },
                  "base_resp": {
                    "status_code": 0,
                    "status_msg": "success"
                  }
                }
                """.formatted(baseUrl(), baseUrl(), baseUrl())));
        server.createContext("/generated-one.jpeg", exchange -> respondBytes(exchange, "image/jpeg", firstImageBytes));
        server.createContext("/generated-two.jpeg", exchange -> respondBytes(exchange, "image/jpeg", secondImageBytes));
        server.createContext("/generated-three.jpeg", exchange -> respondBytes(exchange, "image/jpeg", thirdImageBytes));
        server.start();

        MiniMaxHttpImageClient client = new MiniMaxHttpImageClient(
                new ImageGenerationProperties(
                        new ImageGenerationProperties.MiniMax(baseUrl(), "test-key", "image-01", "16:9", true),
                        null
                ),
                new ObjectMapper()
        );

        MiniMaxImageGenerationResult result = client.generate(new MiniMaxImageGenerationRequest(
                "image-01",
                "A white cat",
                "16:9",
                "url",
                3,
                true,
                null
        ));

        assertEquals(3, result.images().size());
        assertEquals("image/jpeg", result.mimeType());
        assertEquals("image/jpeg", result.images().get(0).mimeType());
        assertEquals("image/jpeg", result.images().get(1).mimeType());
        assertEquals("image/jpeg", result.images().get(2).mimeType());
        assertArrayEquals(firstImageBytes, result.imageBytes());
        assertArrayEquals(secondImageBytes, result.images().get(1).imageBytes());
        assertArrayEquals(thirdImageBytes, result.images().get(2).imageBytes());
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
                        new ImageGenerationProperties.MiniMax(baseUrl(), "test-key", "image-01", "1:1", true),
                        null
                ),
                new ObjectMapper()
        );

        IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> client.generate(new MiniMaxImageGenerationRequest("image-01", "cat", "1:1", "url", 1, true, null))
        );

        assertTrue(error.getMessage().contains("invalid api key"));
        assertTrue(error.getMessage().contains("1008"));
    }

    @Test
    void shouldUseConfiguredReadTimeoutForImageGenerationRequest() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/image_generation", exchange -> {
            try {
                Thread.sleep(1500L);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            respondJson(exchange, """
                    {
                      "id": "slow-request",
                      "data": {
                        "image_urls": ["%s/generated.png"]
                      },
                      "base_resp": {
                        "status_code": 0,
                        "status_msg": "success"
                      }
                    }
                    """.formatted(baseUrl()));
        });
        server.start();

        MiniMaxHttpImageClient client = new MiniMaxHttpImageClient(
                new ImageGenerationProperties(
                        new ImageGenerationProperties.MiniMax(baseUrl(), "test-key", "image-01", "1:1", true, 1, 1),
                        null
                ),
                new ObjectMapper()
        );

        assertThrows(
                RuntimeException.class,
                () -> client.generate(new MiniMaxImageGenerationRequest("image-01", "cat", "1:1", "url", 1, true, null))
        );
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

    private static void respondBytes(HttpExchange exchange, String contentType, byte[] bytes) throws IOException {
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
