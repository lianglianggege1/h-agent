package com.h.backend.chat.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.chat.config.ImageGenerationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
public class MiniMaxHttpImageClient implements MiniMaxImageClient {

    private static final String IMAGE_GENERATION_PATH = "/v1/image_generation";
    private static final int MAX_LOG_BODY_LENGTH = 1000;

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
        String requestId = UUID.randomUUID().toString();
        long startedAt = System.nanoTime();
        log.info(
                "MiniMax image request start requestId={} method=POST url={}{} model={} aspectRatio={} n={} responseFormat={} promptLength={} promptPreview={}",
                requestId,
                minimax.baseUrl(),
                IMAGE_GENERATION_PATH,
                request.model(),
                request.aspectRatio(),
                request.n(),
                request.responseFormat(),
                request.prompt() == null ? 0 : request.prompt().length(),
                truncate(request.prompt(), 120)
        );
        Map<String, Object> body = Map.of(
                "model", request.model(),
                "prompt", request.prompt(),
                "aspect_ratio", request.aspectRatio(),
                "response_format", request.responseFormat(),
                "n", request.n(),
                "prompt_optimizer", request.promptOptimizer()
        );
        try {
            String response = restClient.post()
                    .uri(IMAGE_GENERATION_PATH)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + minimax.apiKey())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .exchange((clientRequest, clientResponse) -> {
                        String responseBody = StreamUtils.copyToString(clientResponse.getBody(), StandardCharsets.UTF_8);
                        if (!clientResponse.getStatusCode().is2xxSuccessful()) {
                            throw new IllegalStateException("MiniMax image request failed with HTTP "
                                    + clientResponse.getStatusCode().value() + ": " + truncate(responseBody, MAX_LOG_BODY_LENGTH));
                        }
                        return responseBody;
                    });
            MiniMaxImageGenerationResult result = parseResponse(response, request.model(), request.responseFormat());
            JsonNode root = readJson(response);
            JsonNode metadata = root.path("metadata");
            JsonNode baseResp = root.path("base_resp");
            log.info(
                    "MiniMax image request end requestId={} httpStatus=200 elapsedMs={} providerRequestId={} successCount={} failedCount={} providerStatusCode={} providerStatusMsg={}",
                    requestId,
                    elapsedMillis(startedAt),
                    result.providerRequestId(),
                    textOrNull(metadata, "success_count"),
                    textOrNull(metadata, "failed_count"),
                    textOrNull(baseResp, "status_code"),
                    textOrNull(baseResp, "status_msg")
            );
            return result;
        } catch (RuntimeException ex) {
            log.warn(
                    "MiniMax image request end requestId={} elapsedMs={} error={}",
                    requestId,
                    elapsedMillis(startedAt),
                    truncate(ex.getMessage(), MAX_LOG_BODY_LENGTH)
            );
            throw ex;
        }
    }

    private MiniMaxImageGenerationResult parseResponse(String response, String model, String responseFormat) {
        try {
            JsonNode root = readJson(response);
            JsonNode baseResp = root.path("base_resp");
            int providerStatusCode = baseResp.path("status_code").asInt(0);
            String providerStatusMessage = textOrNull(baseResp, "status_msg");
            if (providerStatusCode != 0) {
                throw new IllegalStateException("MiniMax image request failed with provider status "
                        + providerStatusCode + ": " + providerStatusMessage);
            }
            String providerRequestId = textOrNull(root, "id");
            JsonNode data = root.path("data");
            if ("url".equalsIgnoreCase(responseFormat)) {
                String imageUrl = firstText(data.path("image_urls"));
                if (imageUrl == null || imageUrl.isBlank()) {
                    throw new IllegalStateException("MiniMax image response did not contain image_urls data");
                }
                byte[] imageBytes = restClient.get()
                        .uri(URI.create(imageUrl))
                        .retrieve()
                        .body(byte[].class);
                if (imageBytes == null || imageBytes.length == 0) {
                    throw new IllegalStateException("MiniMax image url did not return image bytes");
                }
                return new MiniMaxImageGenerationResult(
                        providerRequestId,
                        mimeTypeFromUrl(imageUrl),
                        model,
                        imageBytes,
                        null,
                        null,
                        response
                );
            }
            String base64 = base64Image(root, data);
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
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse MiniMax image response", ex);
        }
    }

    private String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.path(fieldName);
        return value.isMissingNode() || value.isNull() ? null : value.asText();
    }

    private JsonNode readJson(String response) {
        try {
            return objectMapper.readTree(response);
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse MiniMax image response", ex);
        }
    }

    private String base64Image(JsonNode root, JsonNode data) {
        JsonNode firstImage = data.path("image").isArray() ? data.path("image").path(0) : data.path("image");
        String base64 = firstImage.isTextual() ? firstImage.asText() : firstImage.path("base64").asText();
        if (base64 == null || base64.isBlank()) {
            base64 = root.path("image").isArray() ? root.path("image").path(0).asText() : root.path("image").asText();
        }
        return base64;
    }

    private String firstText(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isArray()) {
            return node.isEmpty() ? null : node.path(0).asText();
        }
        return node.asText();
    }

    private String mimeTypeFromUrl(String imageUrl) {
        String lowerUrl = imageUrl.toLowerCase();
        if (lowerUrl.endsWith(".jpg") || lowerUrl.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lowerUrl.endsWith(".webp")) {
            return "image/webp";
        }
        return "image/png";
    }

    private static long elapsedMillis(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000L;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }
}
