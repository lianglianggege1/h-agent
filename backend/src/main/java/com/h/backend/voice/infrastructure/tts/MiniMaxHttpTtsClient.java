package com.h.backend.voice.infrastructure.tts;

import com.h.backend.voice.infrastructure.config.VoiceTtsProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

@Component
public class MiniMaxHttpTtsClient implements MiniMaxTtsClient {

    private static final String TTS_PATH = "/v1/t2a_v2";

    private final VoiceTtsProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public MiniMaxHttpTtsClient(VoiceTtsProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        VoiceTtsProperties.MiniMax minimax = properties.getMinimax();
        this.restClient = RestClient.builder()
                .baseUrl(minimax.getBaseUrl())
                .requestFactory(requestFactory(minimax))
                .build();
    }

    @Override
    public MiniMaxTtsResult synthesize(MiniMaxTtsRequest request) {
        VoiceTtsProperties.MiniMax minimax = properties.getMinimax();
        String voiceId = voiceId(request, minimax);
        Map<String, Object> body = Map.of(
                "model", minimax.getModel(),
                "text", request.text(),
                "stream", false,
                "voice_setting", Map.of("voice_id", voiceId),
                "audio_setting", Map.of(
                        "sample_rate", minimax.getSampleRate(),
                        "bitrate", minimax.getBitrate(),
                        "format", minimax.getFormat()
                )
        );

        String response = restClient.post()
                .uri(TTS_PATH)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + minimax.getApiKey())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .exchange((clientRequest, clientResponse) -> {
                    String responseBody = StreamUtils.copyToString(clientResponse.getBody(), StandardCharsets.UTF_8);
                    if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                        throw new IllegalStateException("MiniMax TTS request failed with HTTP "
                                + clientResponse.getStatusCode().value());
                    }
                    return responseBody;
                });

        return parseResponse(response, minimax.getModel(), voiceId, minimax.getFormat());
    }

    private MiniMaxTtsResult parseResponse(String response, String model, String voiceId, String format) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode baseResp = root.path("base_resp");
            int providerStatusCode = baseResp.path("status_code").asInt(0);
            String providerStatusMessage = textOrNull(baseResp, "status_msg");
            if (providerStatusCode != 0) {
                throw new IllegalStateException("MiniMax TTS request failed with provider status "
                        + providerStatusCode + ": " + providerStatusMessage);
            }
            String audioHex = root.path("data").path("audio").asText();
            if (audioHex == null || audioHex.isBlank()) {
                throw new IllegalStateException("MiniMax TTS response did not contain audio data");
            }
            return new MiniMaxTtsResult(
                    HexFormat.of().parseHex(audioHex),
                    mimeType(format),
                    textOrNull(root, "trace_id"),
                    model,
                    voiceId
            );
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse MiniMax TTS response", ex);
        }
    }

    private static String voiceId(MiniMaxTtsRequest request, VoiceTtsProperties.MiniMax minimax) {
        if (request.voiceId() != null && !request.voiceId().isBlank()) {
            return request.voiceId();
        }
        return minimax.getVoiceId();
    }

    private static String mimeType(String format) {
        if ("wav".equalsIgnoreCase(format)) {
            return "audio/wav";
        }
        return "audio/mpeg";
    }

    private static String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private static JdkClientHttpRequestFactory requestFactory(VoiceTtsProperties.MiniMax minimax) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(minimax.getRequestTimeoutSeconds()));
        return requestFactory;
    }
}
