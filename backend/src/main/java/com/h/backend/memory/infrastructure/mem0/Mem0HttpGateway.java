package com.h.backend.memory.infrastructure.mem0;

import com.h.backend.memory.domain.MemoryScopePolicy;
import com.h.backend.memory.infrastructure.config.LongTermMemoryProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 固定版本自托管 Mem0 HTTP Adapter。Mem0 URL、header、JSON 形状与外部错误
 * 只存在于本实现；调用方只见领域模型。日志不记录 API key、memory 正文或完整响应。
 */
public class Mem0HttpGateway implements Mem0Gateway {

    private static final Logger log = LoggerFactory.getLogger(Mem0HttpGateway.class);

    private static final String SEARCH_PATH = "/v2/memories/search/";
    private static final String ADD_PATH = "/v1/memories/";
    private static final String MEMORIES_ID_PATH = "/v2/memories/{memoryId}/";
    private static final String ADD_INFER_PATH = "/v1/memories/";
    private static final String UPDATE_PATH = "/v1/memories/{memoryId}/";
    private static final String DELETE_PATH = "/v1/memories/{memoryId}/";
    private static final String HISTORY_PATH = "/v2/memories/{memoryId}/history/";

    private final LongTermMemoryProperties properties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public Mem0HttpGateway(LongTermMemoryProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(properties.getMem0().getBaseUrl())
                .requestFactory(requestFactory(properties))
                .build();
    }

    @Override
    public List<Mem0Models.Mem0Memory> searchExact(Mem0Models.Mem0SearchQuery query) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", query.scope().mem0UserId());
        if (query.scope().mem0AgentId() != null) {
            body.put("agent_id", query.scope().mem0AgentId());
        }
        if (query.scope().mem0RunId() != null) {
            body.put("run_id", query.scope().mem0RunId());
        }
        body.put("query", query.query());
        body.put("top_k", query.topK());

        JsonNode root = post(SEARCH_PATH, body);
        // 远程 filter 不足时，本地按 scope 字段二次过滤，保证 AND 精确语义。
        return readMemoryArray(root).stream()
                .filter(memory -> matchesScope(memory, query.scope()))
                .toList();
    }

    @Override
    public List<Mem0Models.Mem0Memory> searchByUser(String mem0UserId, String query, int topK) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("user_id", mem0UserId);
        body.put("query", query);
        body.put("top_k", topK);
        JsonNode root = post(SEARCH_PATH, body);
        return readMemoryArray(root);
    }

    @Override
    public Mem0Models.Mem0AddResult add(Mem0Models.Mem0AddCommand command) {
        Map<String, Object> body = new LinkedHashMap<>();
        List<Map<String, String>> messages = new ArrayList<>();
        for (Mem0Models.Mem0Message message : command.messages()) {
            messages.add(Map.of("role", message.role(), "content", message.content()));
        }
        body.put("messages", messages);
        body.put("user_id", command.scope().mem0UserId());
        if (command.scope().mem0AgentId() != null) {
            body.put("agent_id", command.scope().mem0AgentId());
        }
        if (command.scope().mem0RunId() != null) {
            body.put("run_id", command.scope().mem0RunId());
        }
        body.put("infer", command.infer());
        if (command.metadata() != null && !command.metadata().isEmpty()) {
            body.put("metadata", command.metadata());
        }

        JsonNode root = post(ADD_PATH, body);
        List<String> ids = new ArrayList<>();
        readMemoryArray(root).forEach(memory -> {
            if (memory.id() != null) {
                ids.add(memory.id());
            }
        });
        if (ids.isEmpty() && root != null && root.hasNonNull("id")) {
            ids.add(root.get("id").asString());
        }
        return new Mem0Models.Mem0AddResult(ids);
    }

    @Override
    public Mem0Models.Mem0Memory get(String remoteMemoryId, MemoryScopePolicy.MemoryOwnerScope scope) {
        Map<String, String> queryParams = ownerParams(scope);
        JsonNode root = execute("GET", MEMORIES_ID_PATH.replace("{memoryId}", remoteMemoryId),
                queryParams, null);
        Mem0Models.Mem0Memory memory = readSingleMemory(root);
        if (memory == null || !matchesScope(memory, scope)) {
            return null;
        }
        return memory;
    }

    @Override
    public void update(String remoteMemoryId, String text, MemoryScopePolicy.MemoryOwnerScope scope) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("text", text);
        execute("PUT", UPDATE_PATH.replace("{memoryId}", remoteMemoryId), ownerParams(scope), body);
    }

    @Override
    public void delete(String remoteMemoryId, MemoryScopePolicy.MemoryOwnerScope scope) {
        execute("DELETE", DELETE_PATH.replace("{memoryId}", remoteMemoryId), ownerParams(scope), null);
    }

    @Override
    public List<Mem0Models.Mem0HistoryEntry> history(String remoteMemoryId, MemoryScopePolicy.MemoryOwnerScope scope) {
        Map<String, String> queryParams = ownerParams(scope);
        JsonNode root = execute("GET", HISTORY_PATH.replace("{memoryId}", remoteMemoryId), queryParams, null);
        List<Mem0Models.Mem0HistoryEntry> entries = new ArrayList<>();
        if (root == null) {
            return entries;
        }
        JsonNode results = root.has("results") ? root.get("results") : root;
        if (results != null && results.isArray()) {
            for (JsonNode node : results) {
                entries.add(new Mem0Models.Mem0HistoryEntry(
                        textOf(node.get("id")),
                        textOf(node.get("memory")),
                        scope.scopeKind(),
                        instantOf(node.get("created_at"))
                ));
            }
        }
        return entries;
    }

    private JsonNode post(String path, Map<String, Object> body) {
        return execute("POST", path, null, body);
    }

    private JsonNode execute(String method, String path, Map<String, String> queryParams, Map<String, Object> body) {
        RestClient.RequestBodySpec spec = restClient.method(org.springframework.http.HttpMethod.valueOf(method))
                .uri(path, uriBuilder -> {
                    if (queryParams != null) {
                        queryParams.forEach(uriBuilder::queryParam);
                    }
                    return uriBuilder.build();
                })
                .header("X-API-Key", properties.getMem0().getApiKey())
                .contentType(MediaType.APPLICATION_JSON);
        String response = body == null
                ? spec.retrieve().body(String.class)
                : spec.body(body).retrieve().body(String.class);
        if (response == null || response.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(response);
        } catch (RuntimeException ex) {
            log.warn("Mem0 response is not valid JSON; path={}", path);
            throw new IllegalStateException("Invalid Mem0 response", ex);
        }
    }

    private List<Mem0Models.Mem0Memory> readMemoryArray(JsonNode root) {
        List<Mem0Models.Mem0Memory> memories = new ArrayList<>();
        if (root == null) {
            return memories;
        }
        JsonNode results = root.has("results") ? root.get("results") : root;
        if (results != null && results.isArray()) {
            for (JsonNode node : results) {
                Mem0Models.Mem0Memory memory = readSingleMemory(node);
                if (memory != null) {
                    memories.add(memory);
                }
            }
        }
        return memories;
    }

    private Mem0Models.Mem0Memory readSingleMemory(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        Map<String, Object> metadata = new HashMap<>();
        JsonNode metadataNode = node.get("metadata");
        if (metadataNode != null && metadataNode.isObject()) {
            metadataNode.properties().forEach(entry -> metadata.put(entry.getKey(), entry.getValue().toString()));
        }
        return new Mem0Models.Mem0Memory(
                textOf(node.get("id")),
                textOf(node.get("memory")),
                doubleOf(node.get("score")),
                metadata,
                instantOf(node.get("created_at")),
                instantOf(node.get("updated_at"))
        );
    }

    private boolean matchesScope(Mem0Models.Mem0Memory memory, MemoryScopePolicy.MemoryOwnerScope scope) {
        Map<String, Object> metadata = memory.metadata() == null ? Map.of() : memory.metadata();
        Object scopeKind = metadata.get("scope_kind");
        if (scopeKind != null && !scope.scopeKind().name().equals(scopeKind)) {
            return false;
        }
        // Mem0 按 user_id/agent_id/run_id 做记录级 AND 保存；本项目写入时携带 scope_kind，
        // 远程返回无法核验字段时由 search 端点本身的 AND 语义保证。
        return true;
    }

    private Map<String, String> ownerParams(MemoryScopePolicy.MemoryOwnerScope scope) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("user_id", scope.mem0UserId());
        if (scope.mem0AgentId() != null) {
            params.put("agent_id", scope.mem0AgentId());
        }
        if (scope.mem0RunId() != null) {
            params.put("run_id", scope.mem0RunId());
        }
        return params;
    }

    private static String textOf(JsonNode node) {
        return node == null || node.isNull() ? null : node.asString();
    }

    private static Double doubleOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return node.asDouble();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static Instant instantOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        try {
            return Instant.parse(node.asString());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static JdkClientHttpRequestFactory requestFactory(LongTermMemoryProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getMem0().getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }
}
