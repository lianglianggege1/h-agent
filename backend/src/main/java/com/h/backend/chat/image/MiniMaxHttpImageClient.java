package com.h.backend.chat.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.chat.config.ImageGenerationProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
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
        ImageGenerationProperties.MiniMax minimax = properties.minimaxOrDefault();
        this.restClient = RestClient.builder()
                .baseUrl(minimax.baseUrl())
                .requestFactory(requestFactory(minimax))
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
                List<String> imageUrls = textValues(data.path("image_urls"));
                if (imageUrls.isEmpty()) {
                    throw new IllegalStateException("MiniMax image response did not contain image_urls data");
                }
                List<MiniMaxImageGenerationResult.GeneratedImage> images = new ArrayList<>();
                for (String imageUrl : imageUrls) {
                    byte[] imageBytes = restClient.get()
                            .uri(URI.create(imageUrl))
                            .retrieve()
                            .body(byte[].class);
                    if (imageBytes == null || imageBytes.length == 0) {
                        throw new IllegalStateException("MiniMax image url did not return image bytes");
                    }
                    images.add(new MiniMaxImageGenerationResult.GeneratedImage(
                            mimeTypeFromUrl(imageUrl),
                            imageBytes,
                            null,
                            null
                    ));
                }
                MiniMaxImageGenerationResult.GeneratedImage firstImage = images.getFirst();
                return new MiniMaxImageGenerationResult(
                        providerRequestId,
                        firstImage.mimeType(),
                        model,
                        firstImage.imageBytes(),
                        firstImage.width(),
                        firstImage.height(),
                        response,
                        images
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

    private List<String> textValues(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            for (JsonNode item : node) {
                String value = item.asText();
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
            return values;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? List.of() : List.of(value);
    }

    private String mimeTypeFromUrl(String imageUrl) {
        String lowerPath = URI.create(imageUrl).getPath().toLowerCase();
        if (lowerPath.endsWith(".jpg") || lowerPath.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lowerPath.endsWith(".webp")) {
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

    private static JdkClientHttpRequestFactory requestFactory(ImageGenerationProperties.MiniMax minimax) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofSeconds(minimax.requestTimeoutSeconds()));
        return requestFactory;
    }
}
