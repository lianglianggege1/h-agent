package com.h.backend.generation.infrastructure.provider.minimax;

import com.h.backend.generation.application.port.out.ProviderFilePort;
import com.h.backend.generation.application.port.out.ProviderTaskRejectedException;
import com.h.backend.generation.application.port.out.ProviderTaskQueryPort;
import com.h.backend.generation.application.port.out.TextToVideoSubmissionPort;
import com.h.backend.generation.application.port.out.ImageToVideoSubmissionPort;
import com.h.backend.chat.application.reference.ImageDataUrlEncoder;
import com.h.backend.chat.application.reference.ResolvedReferenceImage;
import com.h.backend.generation.domain.model.ImageToVideoSpec;
import com.h.backend.generation.domain.model.TextToVideoSpec;
import com.h.backend.generation.infrastructure.config.GenerationProperties;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class MiniMaxVideoClient implements TextToVideoSubmissionPort, ImageToVideoSubmissionPort, ProviderTaskQueryPort, ProviderFilePort {
    private static final int SENSITIVE_CONTENT_STATUS_CODE = 1026;

    private final GenerationProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final HttpClient downloadClient;

    public MiniMaxVideoClient(GenerationProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder().baseUrl(properties.getMinimax().getBaseUrl()).build();
        this.downloadClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public String submit(TextToVideoSpec spec) {
        return submitRequest(commonRequest(
                spec.model(), spec.submittedPrompt(), spec.durationSeconds(), spec.resolution(), spec.promptOptimizer(),
                spec.fastPretreatment(), spec.aigcWatermark()
        ));
    }

    @Override
    public String submit(ImageToVideoSpec spec, ResolvedReferenceImage image) {
        Map<String, Object> request = commonRequest(
                spec.model(), spec.submittedPrompt(), spec.durationSeconds(), spec.resolution(), spec.promptOptimizer(),
                spec.fastPretreatment(), spec.aigcWatermark()
        );
        request.put("first_frame_image", ImageDataUrlEncoder.encode(image));
        return submitRequest(request);
    }

    private String submitRequest(Map<String, Object> request) {
        JsonNode response = post("/v1/video_generation", request);
        requireProviderSuccess(response);
        return requiredText(response, "task_id");
    }

    private Map<String, Object> commonRequest(
            String model,
            String prompt,
            int durationSeconds,
            String resolution,
            boolean promptOptimizer,
            boolean fastPretreatment,
            boolean aigcWatermark
    ) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", model);
        request.put("prompt", prompt);
        request.put("duration", durationSeconds);
        request.put("resolution", resolution);
        request.put("prompt_optimizer", promptOptimizer);
        request.put("fast_pretreatment", fastPretreatment);
        request.put("aigc_watermark", aigcWatermark);
        return request;
    }

    @Override
    public ProviderTaskStatus query(String providerTaskId) {
        JsonNode response = get("/v1/query/video_generation", "task_id", providerTaskId);
        requireProviderSuccess(response);
        String rawStatus = requiredText(response, "status");
        ProviderTaskStatus.Status status = switch (rawStatus.toLowerCase()) {
            case "preparing" -> ProviderTaskStatus.Status.PREPARING;
            case "queueing" -> ProviderTaskStatus.Status.QUEUEING;
            case "processing" -> ProviderTaskStatus.Status.PROCESSING;
            case "success" -> ProviderTaskStatus.Status.SUCCESS;
            case "fail", "failed" -> ProviderTaskStatus.Status.FAILED;
            default -> throw new IllegalStateException("Unsupported MiniMax task status: " + rawStatus);
        };
        return new ProviderTaskStatus(status, text(response, "file_id"), text(response.path("base_resp"), "status_msg"));
    }

    @Override
    public DownloadableFile retrieve(String providerFileId) {
        JsonNode response = get("/v1/files/retrieve", "file_id", providerFileId);
        requireProviderSuccess(response);
        JsonNode file = response.path("file");
        return new DownloadableFile(
                providerFileId,
                text(file, "filename") == null ? providerFileId + ".mp4" : text(file, "filename"),
                "video/mp4",
                file.path("bytes").asLong(0),
                requiredText(file, "download_url")
        );
    }

    @Override
    public InputStream openDownload(DownloadableFile file) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(file.downloadUrl()))
                    .timeout(Duration.ofMinutes(5))
                    .GET()
                    .build();
            HttpResponse<InputStream> response = downloadClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IllegalStateException("Video download failed with HTTP " + response.statusCode());
            }
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            if (contentLength > properties.getDownload().getMaxFileSize()) {
                response.body().close();
                throw new IllegalStateException("Video exceeds configured maximum size");
            }
            return response.body();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Video download interrupted", exception);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to open video download stream", exception);
        }
    }

    private JsonNode post(String path, Map<String, Object> body) {
        String response = restClient.post()
                .uri(path)
                .headers(headers -> headers.setBearerAuth(requireApiKey()))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(String.class);
        return parse(response);
    }

    private JsonNode get(String path, String parameterName, String parameterValue) {
        String response = restClient.get()
                .uri(builder -> builder.path(path).queryParam(parameterName, parameterValue).build())
                .headers(headers -> headers.setBearerAuth(requireApiKey()))
                .retrieve()
                .body(String.class);
        return parse(response);
    }

    private JsonNode parse(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (Exception exception) {
            throw new IllegalStateException("MiniMax returned invalid JSON", exception);
        }
    }

    private void requireProviderSuccess(JsonNode response) {
        int code = response.path("base_resp").path("status_code").asInt(0);
        if (code != 0) {
            String message = "MiniMax error " + code + ": " + text(response.path("base_resp"), "status_msg");
            if (code == SENSITIVE_CONTENT_STATUS_CODE) {
                throw new ProviderTaskRejectedException(code, message);
            }
            throw new IllegalStateException(message);
        }
    }

    private String requireApiKey() {
        String apiKey = properties.getMinimax().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("MINIMAX_API_KEY is not configured");
        }
        return apiKey;
    }

    private String requiredText(JsonNode node, String field) {
        String value = text(node, field);
        if (value == null) {
            throw new IllegalStateException("MiniMax response missing " + field);
        }
        return value;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
