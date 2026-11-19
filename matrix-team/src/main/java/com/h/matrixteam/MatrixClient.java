package com.h.matrixteam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 最小 Matrix Client-Server 客户端：登录、/sync、发消息（含 m.mentions 与团队元数据）、加入房间。
 * 只覆盖团队聊天室需要的能力，不做加密与媒体。
 */
public final class MatrixClient {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient http;
    private String accessToken;
    private String userId;
    private String deviceId;

    public MatrixClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public String userId() {
        return userId;
    }

    public String accessToken() {
        return accessToken;
    }

    public String deviceId() {
        return deviceId;
    }

    public void login(String username, String password) {
        ObjectNode body = JSON.createObjectNode();
        body.put("type", "m.login.password");
        body.putObject("identifier").put("type", "m.id.user").put("user", username);
        body.put("password", password);
        JsonNode response = request("POST", "/_matrix/client/v3/login", body, Duration.ofSeconds(30));
        this.accessToken = response.path("access_token").asText();
        this.userId = response.path("user_id").asText();
        this.deviceId = response.path("device_id").asText();
        if (accessToken.isBlank() || userId.isBlank()) {
            throw new IllegalStateException("Matrix login failed for " + username + ": " + response);
        }
    }

    public JsonNode sync(String since, long timeoutMs) {
        StringBuilder path = new StringBuilder("/_matrix/client/v3/sync?timeout=" + timeoutMs);
        if (since != null && !since.isBlank()) {
            path.append("&since=").append(encode(since));
        }
        // 只取房间时间线，忽略 presence/account_data 等无关负载，降低本机开销。
        String filter = "{\"room\":{\"timeline\":{\"limit\":50},\"state\":{\"lazy_load_members\":true}},\"presence\":{\"types\":[]}}";
        path.append("&filter=").append(encode(filter));
        return request("GET", path.toString(), null, Duration.ofMillis(timeoutMs + 15_000));
    }

    public void join(String roomIdOrAlias) {
        request("POST", "/_matrix/client/v3/join/" + encode(roomIdOrAlias), JSON.createObjectNode(), Duration.ofSeconds(30));
    }

    /**
     * 发送文本消息。mentionIds 写入结构化 m.mentions；kind/taskId 写入 com.h.team 团队元数据。
     */
    public String sendText(String roomId, String body, List<String> mentionIds, String kind, String taskId) {
        ObjectNode content = JSON.createObjectNode();
        content.put("msgtype", "m.text");
        content.put("body", body);
        if (mentionIds != null && !mentionIds.isEmpty()) {
            ArrayNode users = content.putObject("m.mentions").putArray("user_ids");
            mentionIds.forEach(users::add);
        }
        if (kind != null && !kind.isBlank()) {
            ObjectNode team = content.putObject(TeamProtocol.CONTENT_KEY);
            team.put("kind", kind);
            if (taskId != null && !taskId.isBlank()) {
                team.put("taskId", taskId);
            }
        }
        String txnId = UUID.randomUUID().toString();
        String path = "/_matrix/client/v3/rooms/" + encode(roomId)
                + "/send/m.room.message/" + encode(txnId);
        JsonNode response = request("PUT", path, content, Duration.ofSeconds(30));
        return response.path("event_id").asText();
    }

    private JsonNode request(String method, String path, JsonNode body, Duration timeout) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + path))
                    .timeout(timeout)
                    .header("Content-Type", "application/json");
            if (accessToken != null) {
                builder.header("Authorization", "Bearer " + accessToken);
            }
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
            builder.method(method, publisher);
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = JSON.readTree(response.body());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Matrix " + method + " " + path + " -> "
                        + response.statusCode() + " " + node.path("error").asText());
            }
            return node;
        } catch (IOException e) {
            throw new IllegalStateException("Matrix request failed: " + method + " " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Matrix request interrupted: " + method + " " + path, e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}