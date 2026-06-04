package com.h.backend.chat.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.chat.config.ImageGenerationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Base64;
import java.util.Map;

@Component
public class MiniMaxHttpImageClient implements MiniMaxImageClient {

    private final ImageGenerationProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public MiniMaxHttpImageClient(ImageGenerationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.minimaxOrDefault().baseUrl())
                .build();
    }

    @Override
    public MiniMaxImageGenerationResult generate(MiniMaxImageGenerationRequest request) {
        ImageGenerationProperties.MiniMax minimax = properties.minimaxOrDefault();
        String response = restClient.post()
                .uri("/v1/image_generation")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + minimax.apiKey())
                .body(Map.of(
                        "model", request.model(),
                        "prompt", request.prompt(),
                        "aspect_ratio", request.aspectRatio(),
                        "response_format", request.responseFormat(),
                        "n", request.n(),
                        "prompt_optimizer", request.promptOptimizer()
                ))
                .retrieve()
                .body(String.class);
        return parseResponse(response, request.model());
    }

    private MiniMaxImageGenerationResult parseResponse(String response, String model) {
        try {
            JsonNode root = objectMapper.readTree(response);
            String providerRequestId = textOrNull(root, "id");
            JsonNode data = root.path("data");
            JsonNode firstImage = data.path("image").isArray() ? data.path("image").path(0) : data.path("image");
            String base64 = firstImage.isTextual() ? firstImage.asText() : firstImage.path("base64").asText();
            if (base64 == null || base64.isBlank()) {
                base64 = root.path("image").isArray() ? root.path("image").path(0).asText() : root.path("image").asText();
            }
            if (base64 == null || base64.isBlank()) {
                throw new IllegalStateException("MiniMax image response did not contain image data");
            }
            return new MiniMaxImageGenerationResult(
                    providerRequestId,
                    "image/png",
                    model,
                    Base64.getDecoder().decode(base64),
                    null,
                    null,
                    response
            );
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse MiniMax image response", ex);
        }
    }

    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }
}
