package com.h.backend.generation.infrastructure.provider.minimax;

import com.h.backend.generation.application.port.out.MusicProviderException;
import com.h.backend.generation.application.port.out.MusicProviderPort;
import com.h.backend.generation.domain.model.MusicGenerationSpec;
import com.h.backend.generation.infrastructure.config.GenerationProperties;
import com.h.backend.generation.infrastructure.config.MusicGenerationProperties;

import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
public class MiniMaxMusicClient implements MusicProviderPort {
    private final GenerationProperties generation;
    private final MusicGenerationProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();

    public MiniMaxMusicClient(GenerationProperties generation, MusicGenerationProperties properties, ObjectMapper json) {
        this.generation = generation;
        this.properties = properties;
        this.json = json;
        if (properties.getRequestTimeout() == null || properties.getRequestTimeout().isNegative()
                || properties.getRequestTimeout().isZero()) throw new IllegalArgumentException("音乐接口超时必须为正数");
        if (properties.getMaxAudioBytes() < 1 || properties.getMaxAudioBytes() > 128 * 1024 * 1024)
            throw new IllegalArgumentException("音乐文件大小上限必须在 1 字节至 128 MiB 之间");
    }

    @Override
    public Lyrics writeLyrics(MusicGenerationSpec spec) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("mode", "write_full_song");
        body.put("prompt", spec.prompt());
        if (spec.title() != null && !spec.title().isBlank()) body.put("title", spec.title());
        JsonNode result = post("/v1/lyrics_generation", body, 256 * 1024);
        String lyrics = requiredText(result, "lyrics");
        MusicGenerationSpec.requireLyrics(lyrics);
        return new Lyrics(result.path("song_title").asText(""), result.path("style_tags").asText(""), lyrics);
    }

    @Override
    public byte[] sing(String lyrics, String prompt) {
        MusicGenerationSpec.requireLyrics(lyrics);
        // Validate again at the paid boundary, including lyrics written by the provider.
        new MusicGenerationSpec(prompt, lyrics, null);
        JsonNode result = post("/v1/music_generation", Map.of(
                "model", "music-3.0",
                "lyrics", lyrics,
                "prompt", prompt == null ? "根据歌词选择适合的曲风、情绪和人声，完整演唱原文，配有伴奏" : prompt,
                "lyrics_optimizer", false,
                "is_instrumental", false,
                "stream", false,
                "output_format", "hex",
                "audio_setting", Map.of("sample_rate", 44100, "bitrate", 256000, "format", "mp3")
        ), properties.getMaxAudioBytes() * 2 + 1024 * 1024);
        if (result.path("data").path("status").asInt(-1) != 2)
            throw new MusicProviderException("MiniMax 未返回已完成的歌曲；不会自动重复生成", true);
        String hex = requiredText(result.path("data"), "audio");
        if (hex.length() > properties.getMaxAudioBytes() * 2)
            throw new MusicProviderException("生成的歌曲超过文件大小上限，未保存；不会自动重复生成", false);
        try {
            byte[] bytes = HexFormat.of().parseHex(hex);
            if (bytes.length == 0) throw new IllegalArgumentException();
            return bytes;
        } catch (IllegalArgumentException ex) {
            throw new MusicProviderException("MiniMax 返回的音频数据无效；不会自动重复生成", false);
        }
    }

    private JsonNode post(String path, Map<String, Object> body, int maxResponseBytes) {
        String key = generation.getMinimax().getApiKey();
        if (key == null || key.isBlank())
            throw new MusicProviderException("未配置 MiniMax API Key，请由管理员配置 MINIMAX_API_KEY", false);
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.getBaseUrl().replaceAll("/+$", "") + path))
                .timeout(properties.getRequestTimeout())
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body), StandardCharsets.UTF_8))
                .build();
        var pending = http.sendAsync(request,
                HttpResponse.BodyHandlers.limiting(HttpResponse.BodyHandlers.ofByteArray(), maxResponseBytes));
        try {
            // Bound the entire body transfer as well as waiting for response headers.
            HttpResponse<byte[]> response = pending.get(properties.getRequestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                String detail = switch (status) {
                    case 401, 403 -> "鉴权失败或账号未开通音乐 API 权限";
                    case 429 -> "请求受到限流，请稍后手动重试";
                    default -> "接口返回 HTTP " + status;
                };
                throw new MusicProviderException("MiniMax " + detail + "；不会自动重复生成", status >= 500 || status == 408);
            }
            JsonNode result = json.readTree(response.body());
            JsonNode codeNode = result.path("base_resp").path("status_code");
            if (!codeNode.isIntegralNumber())
                throw new MusicProviderException("MiniMax 响应缺少状态码，生成结果待确认；不会自动重复生成", true);
            int code = codeNode.asInt();
            if (code != 0) {
                String detail = switch (code) {
                    case 1002 -> "请求受到限流";
                    case 1004, 2049 -> "API Key 无效或账号未开通音乐 API 权限";
                    case 1008 -> "账号余额不足";
                    case 1026 -> "输入内容未通过审核";
                    case 2013 -> "请求参数不符合要求";
                    default -> "接口调用失败";
                };
                throw new MusicProviderException("MiniMax " + detail + "（状态码 " + code + "）；不会自动重复生成", false);
            }
            return result;
        } catch (MusicProviderException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MusicProviderException("MiniMax 请求被中断，生成结果待确认；不会自动重复生成", true);
        } catch (Exception ex) {
            // Do not echo HTTP exception payloads, which can contain credentials or lyric text.
            throw new MusicProviderException("MiniMax 请求超时、连接失败或响应异常，生成结果待确认；不会自动重复生成", true);
        } finally {
            if (!pending.isDone()) pending.cancel(true);
        }
    }

    private String requiredText(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (!value.isString() || value.asText().isBlank())
            throw new MusicProviderException("MiniMax 响应缺少有效的 " + field + "；不会自动重复生成", true);
        return value.asText();
    }
}
