package com.h.backend.voice;

import tools.jackson.databind.ObjectMapper;
import com.h.backend.voice.infrastructure.config.VoiceTtsProperties;
import com.h.backend.voice.infrastructure.tts.MiniMaxHttpTtsClient;
import com.h.backend.voice.infrastructure.tts.MiniMaxTtsRequest;
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

class MiniMaxHttpTtsClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void sendsTtsRequestAndParsesHexAudio() throws Exception {
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<String> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/t2a_v2", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, """
                    {"data":{"audio":"010203"},"trace_id":"trace-1","base_resp":{"status_code":0,"status_msg":"success"}}
                    """);
        });
        server.start();

        VoiceTtsProperties properties = new VoiceTtsProperties();
        properties.getMinimax().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getMinimax().setApiKey("test-key");
        properties.getMinimax().setModel("speech-2.8-turbo");
        properties.getMinimax().setVoiceId("male-qn-qingse");
        MiniMaxHttpTtsClient client = new MiniMaxHttpTtsClient(properties, new ObjectMapper());

        var result = client.synthesize(new MiniMaxTtsRequest("你好", null));

        assertEquals("Bearer test-key", authorization.get());
        assertTrue(body.get().contains("\"model\":\"speech-2.8-turbo\""));
        assertTrue(body.get().contains("\"text\":\"你好\""));
        assertTrue(body.get().contains("\"voice_id\":\"male-qn-qingse\""));
        assertArrayEquals(new byte[]{1, 2, 3}, result.audioBytes());
        assertEquals("audio/mpeg", result.mimeType());
        assertEquals("trace-1", result.providerRequestId());
    }

    @Test
    void throwsWhenProviderReturnsErrorStatus() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/t2a_v2", exchange -> respond(exchange, """
                {"base_resp":{"status_code":1001,"status_msg":"bad voice"}}
                """));
        server.start();

        VoiceTtsProperties properties = new VoiceTtsProperties();
        properties.getMinimax().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getMinimax().setApiKey("test-key");
        MiniMaxHttpTtsClient client = new MiniMaxHttpTtsClient(properties, new ObjectMapper());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> client.synthesize(new MiniMaxTtsRequest("你好", null))
        );
        assertTrue(error.getMessage().contains("1001"));
        assertTrue(error.getMessage().contains("bad voice"));
    }

    @Test
    void doesNotLeakHttpErrorResponseBodyInExceptionMessage() throws Exception {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/v1/t2a_v2", exchange -> respond(exchange, """
                {"error":"bad","text":"用户敏感文本","apiKey":"secret-key"}
                """, 500));
        server.start();

        VoiceTtsProperties properties = new VoiceTtsProperties();
        properties.getMinimax().setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.getMinimax().setApiKey("test-key");
        MiniMaxHttpTtsClient client = new MiniMaxHttpTtsClient(properties, new ObjectMapper());

        IllegalStateException error = assertThrows(
                IllegalStateException.class,
                () -> client.synthesize(new MiniMaxTtsRequest("用户敏感文本", null))
        );

        assertTrue(error.getMessage().contains("500"));
        assertTrue(!error.getMessage().contains("用户敏感文本"));
        assertTrue(!error.getMessage().contains("secret-key"));
        assertTrue(!error.getMessage().contains("bad"));
    }

    private void respond(HttpExchange exchange, String body) throws IOException {
        respond(exchange, body, 200);
    }

    private void respond(HttpExchange exchange, String body, int statusCode) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
