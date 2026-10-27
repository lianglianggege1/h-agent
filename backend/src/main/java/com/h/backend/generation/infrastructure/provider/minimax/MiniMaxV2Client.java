package com.h.backend.generation.infrastructure.provider.minimax;

import com.h.backend.generation.application.port.out.ProviderTaskRejectedException;
import com.h.backend.generation.application.port.out.VideoSubmissionUncertainException;
import com.h.backend.generation.domain.model.VideoV2Spec;
import com.h.backend.generation.infrastructure.config.GenerationProperties;

import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

/**
 * V2 wire boundary. No automatic POST retry and no application-user data in account-wide list
 * responses.
 */
@Component
public class MiniMaxV2Client
        implements com.h.backend.generation.application.port.out.VideoV2ProviderPort {
    private final GenerationProperties properties;
    private final ObjectMapper json;
    private final RestClient http;

    public MiniMaxV2Client(GenerationProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
        var factory =
                new JdkClientHttpRequestFactory(
                        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(Duration.ofSeconds(60));
        http =
                RestClient.builder()
                        .requestFactory(factory)
                        .baseUrl(properties.getMinimax().getVideoV2BaseUrl())
                        .build();
    }

    public String create(VideoV2Spec spec, List<Map<String, Object>> content) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", spec.model());
        String path =
                switch (spec.taskType()) {
                    case "h3_context_ir" -> "/v2/h3_context_ir";
                    case "regeneration" -> "/v2/video_regeneration";
                    default -> "/v2/video_generation";
                };
        if (spec.sourceTaskId() != null) body.put("source_task_id", spec.sourceTaskId());
        else body.put("content", content);
        if (!"h3_context_ir".equals(spec.taskType())) body.put("resolution", spec.resolution());
        if (!"regeneration".equals(spec.taskType())) {
            body.put("duration", spec.durationSeconds());
            body.put("ratio", spec.ratio());
        }
        try {
            JsonNode created = request(HttpMethod.POST, URI.create(path), body);
            if (created.path("task_id").asText("").isBlank())
                throw new VideoSubmissionUncertainException(
                        new IllegalStateException("创建响应未返回task_id"));
            return created.path("task_id").asText();
        } catch (tools.jackson.core.JacksonException ex) {
            throw new VideoSubmissionUncertainException(ex);
        } catch (ResourceAccessException ex) {
            throw new VideoSubmissionUncertainException(ex);
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is5xxServerError())
                throw new VideoSubmissionUncertainException(ex);
            throw ex;
        }
    }

    public JsonNode query(String id) {
        return request(HttpMethod.GET, URI.create("/v2/query/video_generation/" + safeId(id)), null)
                .path("task");
    }

    public JsonNode list(
            List<String> ownedIds,
            int page,
            int size,
            String status,
            String model,
            String taskType) {
        if (ownedIds.isEmpty()) return json.valueToTree(Map.of("items", List.of(), "total", 0));
        // Never omit this filter: the provider API key can be shared by many application users.
        var uri =
                org.springframework.web.util.UriComponentsBuilder.fromPath(
                                "/v2/query/video_generation")
                        .queryParam("page_num", page)
                        .queryParam("page_size", size);
        ownedIds.forEach(id -> uri.queryParam("filter.task_ids", safeId(id)));
        if (status != null && !status.isBlank()) uri.queryParam("filter.status", status);
        if (model != null && !model.isBlank()) uri.queryParam("filter.model", model);
        if (taskType != null && !taskType.isBlank()) uri.queryParam("filter.task_type", taskType);
        JsonNode response = request(HttpMethod.GET, uri.build().encode().toUri(), null);
        // Defense in depth: ignore unexpected account-wide items even if upstream ignores its
        // filter.
        List<JsonNode> safe = new ArrayList<>();
        response.path("items")
                .forEach(
                        task -> {
                            if (ownedIds.contains(task.path("task_id").asText())) safe.add(task);
                        });
        return json.valueToTree(Map.of("items", safe, "total", safe.size()));
    }

    public JsonNode delete(String id) {
        return request(HttpMethod.DELETE, URI.create("/v2/video_generation/" + safeId(id)), null);
    }

    public TaskInfo inspect(String id) {
        return taskInfo(query(id), id);
    }

    public List<TaskInfo> listOwned(
            List<String> ids, int page, int size, String status, String model, String type) {
        List<TaskInfo> result = new ArrayList<>();
        list(ids, page, size, status, model, type)
                .path("items")
                .forEach(item -> result.add(taskInfo(item, item.path("task_id").asText())));
        return List.copyOf(result);
    }

    public Removal remove(String id) {
        JsonNode response = delete(id);
        return new Removal(response.path("action").asText(), response.path("status").asText());
    }

    private TaskInfo taskInfo(JsonNode task, String id) {
        return new TaskInfo(
                id,
                task.path("status").asText(),
                task.path("task_type").asText(),
                task.path("content").path("prompt").asText(null),
                task.path("content").path("url").asText(null),
                task.path("error").path("message").asText(null));
    }

    private JsonNode request(HttpMethod method, URI uri, Map<String, Object> body) {
        String key = properties.getMinimax().getApiKey();
        if (key == null || key.isBlank()) throw new IllegalStateException("MINIMAX_API_KEY未配置");
        var request = http.method(method).uri(uri.toString()).headers(h -> h.setBearerAuth(key));
        if (body != null) {
            String encoded = json.writeValueAsString(body);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > 64L * 1024 * 1024)
                throw new IllegalArgumentException("视频请求超过64MB，请减少素材大小");
            request.contentType(MediaType.APPLICATION_JSON).body(encoded);
        }
        try {
            String response = request.retrieve().body(String.class);
            if (response == null || response.isBlank()) {
                if (method == HttpMethod.POST)
                    throw new VideoSubmissionUncertainException(
                            new IllegalStateException("创建响应为空"));
                throw new IllegalStateException("视频接口返回空响应");
            }
            JsonNode result = json.readTree(response);
            if (result.has("error") || result.path("base_resp").path("status_code").asInt(0) != 0)
                throw new ProviderTaskRejectedException(
                        result.path("base_resp").path("status_code").asInt(400),
                        "供应商拒绝视频请求：" + result.path("error").path("message").asText("请检查素材和参数"));
            return result;
        } catch (RestClientResponseException ex) {
            if (ex.getStatusCode().is4xxClientError() && ex.getStatusCode().value() != 429)
                throw new ProviderTaskRejectedException(
                        ex.getStatusCode().value(),
                        "视频接口拒绝请求（HTTP " + ex.getStatusCode().value() + "），请检查任务、账号权限及素材规格");
            throw ex;
        }
    }

    private static String safeId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,128}"))
            throw new IllegalArgumentException("非法供应商任务ID");
        return id;
    }
}
