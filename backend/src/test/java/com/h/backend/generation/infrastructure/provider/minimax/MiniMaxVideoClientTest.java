package com.h.backend.generation.infrastructure.provider.minimax;

import com.h.backend.generation.application.port.out.ProviderTaskRejectedException;
import com.h.backend.generation.infrastructure.config.GenerationProperties;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MiniMaxVideoClientTest {
    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void mapsSensitiveContentErrorToNonRetryableProviderRejection() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/query/video_generation", exchange -> respondJson(exchange, """
                {
                  "base_resp": {
                    "status_code": 1026,
                    "status_msg": "input new_sensitive, input first_frame_image sensitive"
                  }
                }
                """));
        server.start();

        GenerationProperties properties = new GenerationProperties();
        properties.getMinimax().setBaseUrl(baseUrl());
        properties.getMinimax().setApiKey("test-key");
        MiniMaxVideoClient client = new MiniMaxVideoClient(properties, new ObjectMapper());

        ProviderTaskRejectedException error = assertThrows(
                ProviderTaskRejectedException.class,
                () -> client.query("provider-task-1")
        );

        assertEquals(1026, error.providerStatusCode());
        assertEquals(
                "MiniMax error 1026: input new_sensitive, input first_frame_image sensitive",
                error.getMessage()
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
}
