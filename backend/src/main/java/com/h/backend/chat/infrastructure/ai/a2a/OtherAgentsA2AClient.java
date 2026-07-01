package com.h.backend.chat.infrastructure.ai.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.h.backend.chat.infrastructure.config.OtherAgentsA2AProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class OtherAgentsA2AClient {

    private final OtherAgentsA2AProperties properties;
    private final RestClient restClient;

    public OtherAgentsA2AClient(OtherAgentsA2AProperties properties) {
        this.properties = properties;
        this.restClient = RestClient.builder().build();
    }

    public String generateStory(String topic) {
        return send("/creative-writer/a2a", List.of(topic));
    }

    public String editForAudience(String story, String audience) {
        return send("/audience-editor/a2a", List.of(story, audience));
    }

    public String editForStyle(String story, String style) {
        return send("/style-editor/a2a", List.of(story, style));
    }

    private String send(String path, List<String> texts) {
        JsonNode response = restClient.post()
                .uri(normalizedBaseUrl() + path)
                .contentType(MediaType.APPLICATION_JSON)
                .body(requestBody(texts))
                .retrieve()
                .body(JsonNode.class);

        if (response == null) {
            throw new IllegalStateException("other-agents A2A response is empty");
        }
        JsonNode error = response.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new IllegalStateException("other-agents A2A error: " + error);
        }
        JsonNode parts = response.path("result").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new IllegalStateException("other-agents A2A response contains no text parts");
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : parts) {
            String value = part.path("text").asText("");
            if (!value.isBlank()) {
                if (!text.isEmpty()) {
                    text.append("\n");
                }
                text.append(value);
            }
        }
        if (text.isEmpty()) {
            throw new IllegalStateException("other-agents A2A response text is blank");
        }
        return text.toString();
    }

    private Map<String, Object> requestBody(List<String> texts) {
        List<Map<String, Object>> parts = texts.stream()
                .map(text -> Map.<String, Object>of("kind", "text", "text", text))
                .toList();
        return Map.of(
                "jsonrpc", "2.0",
                "id", UUID.randomUUID().toString(),
                "method", "message/send",
                "params", Map.of(
                        "message", Map.of(
                                "role", "user",
                                "parts", parts,
                                "messageId", UUID.randomUUID().toString()
                        )
                )
        );
    }

    private String normalizedBaseUrl() {
        String baseUrl = properties.getBaseUrl();
        if (baseUrl == null || baseUrl.isBlank()) {
            return "http://localhost:8082";
        }
        return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }
}
