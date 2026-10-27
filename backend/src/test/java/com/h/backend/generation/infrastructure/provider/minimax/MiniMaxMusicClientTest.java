package com.h.backend.generation.infrastructure.provider.minimax;

import com.h.backend.generation.application.port.out.MusicProviderException;
import com.h.backend.generation.domain.model.MusicGenerationSpec;
import com.h.backend.generation.infrastructure.config.GenerationProperties;
import com.h.backend.generation.infrastructure.config.MusicGenerationProperties;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class MiniMaxMusicClientTest {
    @Test
    void requestsOriginalLyricsAndMp3WithoutOptimizingOrInstrumentalMode() throws Exception {
        try (var api = new Api(200, "{\"base_resp\":{\"status_code\":0},\"data\":{\"status\":2,\"audio\":\"494433\"}}")) {
            String lyrics = "[Verse]\n 原文的空格 \n重复\n重复";
            assertArrayEquals(new byte[]{'I', 'D', '3'}, api.client().sing(lyrics, "民谣女声"));
            JsonNode request = api.request.get();
            assertEquals("music-3.0", request.path("model").asText());
            assertEquals(lyrics, request.path("lyrics").asText());
            assertEquals("民谣女声", request.path("prompt").asText());
            assertFalse(request.path("lyrics_optimizer").asBoolean(true));
            assertFalse(request.path("is_instrumental").asBoolean(true));
            assertFalse(request.path("stream").asBoolean(true));
            assertEquals("hex", request.path("output_format").asText());
            assertEquals("mp3", request.path("audio_setting").path("format").asText());
            assertEquals(44100, request.path("audio_setting").path("sample_rate").asInt());
            assertEquals(1, api.calls.get());
        }
    }

    @Test
    void usesDedicatedLyricsEndpointForThemeAndRetainsTitle() throws Exception {
        try (var api = new Api(200, "{\"base_resp\":{\"status_code\":0},\"song_title\":\"海风\",\"style_tags\":\"Pop\",\"lyrics\":\"[Verse]\\n海风吹过\"}")) {
            var result = api.client().writeLyrics(new MusicGenerationSpec("夏日海边", null, "海风"));
            assertEquals("/v1/lyrics_generation", api.path.get());
            assertEquals("write_full_song", api.request.get().path("mode").asText());
            assertEquals("海风", api.request.get().path("title").asText());
            assertEquals("[Verse]\n海风吹过", result.lyrics());
            assertEquals("Pop", result.styleTags());
        }
    }

    @Test
    void serverFailureIsUnknownAndNeverRepeatsThePaidPost() throws Exception {
        try (var api = new Api(502, "sensitive provider payload")) {
            var error = assertThrows(MusicProviderException.class, () -> api.client().sing("原文", null));
            assertTrue(error.resultUnknown());
            assertFalse(error.getMessage().contains("sensitive"));
            assertEquals(1, api.calls.get());
        }
    }

    @Test
    void balanceAndPermissionErrorsAreVisibleWithoutExposingProviderPayload() throws Exception {
        for (int code : new int[]{1004, 1008, 2049, 1026}) {
            try (var api = new Api(200, "{\"base_resp\":{\"status_code\":" + code + ",\"status_msg\":\"secret-key\"}}")) {
                var error = assertThrows(MusicProviderException.class, () -> api.client().sing("原文", null));
                assertFalse(error.resultUnknown());
                assertTrue(error.getMessage().contains(Integer.toString(code)));
                assertFalse(error.getMessage().contains("secret-key"));
                assertEquals(1, api.calls.get());
            }
        }
    }

    @Test
    void malformedSuccessCannotBecomeAnEmptyOrTruncatedAudioArtifact() throws Exception {
        for (String response : new String[]{"{}", "{\"base_resp\":{\"status_code\":0},\"data\":{\"status\":1}}",
                "{\"base_resp\":{\"status_code\":0},\"data\":{\"status\":2,\"audio\":\"bad-hex\"}}"}) {
            try (var api = new Api(200, response)) {
                assertThrows(MusicProviderException.class, () -> api.client().sing("原文", null));
                assertEquals(1, api.calls.get());
            }
        }
    }

    @Test
    void validatesLengthBeforeAnyPaidRequest() throws Exception {
        try (var api = new Api(200, "{}")) {
            assertThrows(IllegalArgumentException.class, () -> api.client().sing("字".repeat(3501), null));
            assertEquals(0, api.calls.get());
        }
    }

    @Test
    void boundsTheBodyTransferEvenAfterResponseHeadersArrive() throws Exception {
        try (var api = new Api(200, "", true)) {
            api.music.setRequestTimeout(Duration.ofMillis(700));
            assertTimeout(Duration.ofSeconds(3), () -> {
                var error = assertThrows(MusicProviderException.class, () -> api.client().sing("原文", null));
                assertTrue(error.resultUnknown());
            });
            assertEquals(1, api.calls.get());
        }
    }

    @Test
    void oversizedAudioIsRejectedRatherThanSilentlyTruncated() throws Exception {
        try (var api = new Api(200, "{\"base_resp\":{\"status_code\":0},\"data\":{\"status\":2,\"audio\":\"494433\"}}")) {
            api.music.setMaxAudioBytes(2);
            var error = assertThrows(MusicProviderException.class, () -> api.client().sing("原文", null));
            assertTrue(error.getMessage().contains("大小上限"));
            assertEquals(1, api.calls.get());
        }
    }

    private static class Api implements AutoCloseable {
        final HttpServer server;
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<JsonNode> request = new AtomicReference<>();
        final AtomicReference<String> path = new AtomicReference<>();
        final ObjectMapper json = new ObjectMapper();
        final MusicGenerationProperties music = new MusicGenerationProperties();
        final CountDownLatch release = new CountDownLatch(1);
        Api(int status, String response) throws Exception {
            this(status, response, false);
        }
        Api(int status, String response, boolean stallBody) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/v1/", exchange -> {
                calls.incrementAndGet();
                path.set(exchange.getRequestURI().getPath());
                request.set(json.readTree(exchange.getRequestBody()));
                if (stallBody) {
                    exchange.sendResponseHeaders(status, 0);
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                    try { release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    exchange.close();
                    return;
                }
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            music.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
            music.setRequestTimeout(Duration.ofSeconds(5));
        }
        MiniMaxMusicClient client() {
            var generation = new GenerationProperties();
            generation.getMinimax().setApiKey("fixture");
            return new MiniMaxMusicClient(generation, music, json);
        }
        @Override public void close() { release.countDown(); server.stop(0); }
    }
}
