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
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 最小 Matrix Client-Server 客户端：登录、/sync、发文本与媒体消息（含 m.mentions 与团队元数据）、
 * 加入房间、媒体上传/下载。只覆盖团队聊天室需要的能力，不做加密。
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

    /**
     * 从已登录的 Matrix userId（{@code @local:server_name}）解析 server_name。
     * 登录成功后 userId 必带域名，这里不提供示例域名兜底，缺失即视为装配错误直接抛出。
     */
    public static String domainOf(String userId) {
        int colon = userId == null ? -1 : userId.indexOf(':');
        if (colon < 0 || colon == userId.length() - 1) {
            throw new IllegalStateException("Matrix userId missing server_name: " + userId);
        }
        return userId.substring(colon + 1);
    }

    public void login(String username, String password) {
        ObjectNode body = JSON.createObjectNode();
        body.put("type", "m.login.password");
        body.putObject("identifier").put("type", "m.id.user").put("user", username);
        body.put("password", password);
        // Synapse 登录限流（429）且被拒请求也消耗配额，退避间隔必须大于配额恢复速度（约 6s/次）。
        for (int attempt = 0; ; attempt++) {
            try {
                JsonNode response = request("POST", "/_matrix/client/v3/login", body, Duration.ofSeconds(30));
                this.accessToken = response.path("access_token").asText();
                this.userId = response.path("user_id").asText();
                this.deviceId = response.path("device_id").asText();
                if (accessToken.isBlank() || userId.isBlank()) {
                    throw new IllegalStateException("Matrix login failed for " + username + ": " + response);
                }
                return;
            } catch (IllegalStateException e) {
                if (attempt >= 4 || !e.getMessage().contains("429")) {
                    throw e;
                }
                sleep(15_000L << attempt);
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Matrix login retry interrupted", e);
        }
    }

    /**
     * 用 Synapse 共享密钥注册新账号（/_synapse/admin/v1/register，nonce + HMAC-SHA1）。
     * username 为 localpart（如 u116）；成功后返回完整 userId 并填充本客户端会话。
     * 无需管理员 token，仅依赖 homeserver.yaml 的 registration_shared_secret。
     */
    public String register(String sharedSecret, String username, String password) {
        JsonNode nonceResponse = request("GET", "/_synapse/admin/v1/register", null, Duration.ofSeconds(30));
        String nonce = nonceResponse.path("nonce").asText();
        if (nonce.isBlank()) {
            throw new IllegalStateException("Matrix register nonce missing: " + nonceResponse);
        }
        String mac = hmacSha1(sharedSecret, nonce + "\0" + username + "\0" + password + "\0" + "notadmin");
        ObjectNode body = JSON.createObjectNode();
        body.put("nonce", nonce);
        body.put("username", username);
        body.put("password", password);
        body.put("mac", mac);
        body.put("admin", false);
        JsonNode response = request("POST", "/_synapse/admin/v1/register", body, Duration.ofSeconds(30));
        this.userId = response.path("user_id").asText();
        this.accessToken = response.path("access_token").asText();
        this.deviceId = response.path("device_id").asText();
        if (userId.isBlank()) {
            throw new IllegalStateException("Matrix register failed for " + username + ": " + response);
        }
        return userId;
    }

    private static String hmacSha1(String secret, String message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] raw = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(raw.length * 2);
            for (byte b : raw) {
                hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
            }
            return hex.toString();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Matrix register HMAC failed", e);
        }
    }

    /** 邀请目标账号加入房间（invite-only 房间须先邀请再 join）。 */
    public void invite(String roomId, String targetUserId) {
        ObjectNode body = JSON.createObjectNode();
        body.put("user_id", targetUserId);
        request("POST", "/_matrix/client/v3/rooms/" + encode(roomId) + "/invite", body, Duration.ofSeconds(30));
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

    /** 本账号已加入的房间 ID 列表（{joined_rooms:[...]}）。 */
    public JsonNode joinedRooms() {
        return request("GET", "/_matrix/client/v3/joined_rooms", null, Duration.ofSeconds(30));
    }

    /** 房间成员事件（{chunk:[m.room.member...]}），state_key 为成员 userId。 */
    public JsonNode roomMembers(String roomId) {
        return request("GET", "/_matrix/client/v3/rooms/" + encode(roomId) + "/members", null, Duration.ofSeconds(30));
    }

    /** 房间名称（m.room.name）；未设置或不可读时返回 null。 */
    public String roomName(String roomId) {
        String path = "/_matrix/client/v3/rooms/" + encode(roomId) + "/state/m.room.name";
        try {
            JsonNode node = request("GET", path, null, Duration.ofSeconds(15));
            String name = node.path("name").asText(null);
            return name == null || name.isBlank() ? null : name;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 与目标成员建立（或复用由调用方判重）单聊房间，返回 roomId；并写 m.direct 便于客户端归类。 */
    public String createDm(String targetUserId) {
        ObjectNode body = JSON.createObjectNode();
        body.put("preset", "trusted_private_chat");
        body.put("is_direct", true);
        body.putArray("invite").add(targetUserId);
        JsonNode response = request("POST", "/_matrix/client/v3/createRoom", body, Duration.ofSeconds(30));
        String roomId = response.path("room_id").asText();
        if (roomId.isBlank()) {
            throw new IllegalStateException("Matrix createRoom failed: " + response);
        }
        markDirect(targetUserId, roomId);
        return roomId;
    }

    /**
     * 创建某用户专属的私密团队房间（invite-only），邀请三个 bot 账号加入；创建者（用户）自动成为成员。
     * 返回 roomId。bot 经各自桥接的 /sync 自动接受邀请后进房，房间消息天然按用户隔离。
     */
    public String createTeamRoom(String name, List<String> inviteUserIds) {
        ObjectNode body = JSON.createObjectNode();
        body.put("preset", "private_chat");
        body.put("visibility", "private");
        body.put("name", name);
        ArrayNode invite = body.putArray("invite");
        inviteUserIds.forEach(invite::add);
        JsonNode response = request("POST", "/_matrix/client/v3/createRoom", body, Duration.ofSeconds(30));
        String roomId = response.path("room_id").asText();
        if (roomId.isBlank()) {
            throw new IllegalStateException("Matrix createTeamRoom failed: " + response);
        }
        return roomId;
    }

    private void markDirect(String targetUserId, String roomId) {
        String path = "/_matrix/client/v3/user/" + encode(userId) + "/account_data/m.direct";
        ObjectNode direct;
        try {
            JsonNode existing = request("GET", path, null, Duration.ofSeconds(15));
            direct = existing.isObject() ? (ObjectNode) existing : JSON.createObjectNode();
        } catch (RuntimeException e) {
            direct = JSON.createObjectNode();
        }
        ArrayNode rooms = direct.has(targetUserId) && direct.get(targetUserId).isArray()
                ? (ArrayNode) direct.get(targetUserId) : direct.putArray(targetUserId);
        boolean present = false;
        for (JsonNode node : rooms) {
            if (roomId.equals(node.asText())) {
                present = true;
                break;
            }
        }
        if (!present) {
            rooms.add(roomId);
        }
        request("PUT", path, direct, Duration.ofSeconds(15));
    }

    /**
     * 反向分页读取房间历史（最新在前）。before 传上一次响应的 end 令牌可继续向更早翻页。
     */
    public JsonNode messages(String roomId, int limit, String before) {
        StringBuilder path = new StringBuilder("/_matrix/client/v3/rooms/")
                .append(encode(roomId)).append("/messages?dir=b&limit=").append(limit);
        if (before != null && !before.isBlank()) {
            path.append("&from=").append(encode(before));
        }
        path.append("&filter=").append(encode("{\"types\":[\"m.room.message\"]}"));
        return request("GET", path.toString(), null, Duration.ofSeconds(30));
    }

    /**
     * 发送文本消息。mentionIds 写入结构化 m.mentions；kind/taskId 写入 com.h.team 团队元数据。
     */
    public String sendText(String roomId, String body, List<String> mentionIds, String kind, String taskId) {
        return sendText(roomId, body, mentionIds, kind, taskId, null);
    }

    /** originUserId 为该消息背后的工作台用户 id（委派/汇报/提问的归属链），写入 com.h.team.userId。 */
    public String sendText(String roomId, String body, List<String> mentionIds, String kind, String taskId,
                           String originUserId) {
        ObjectNode content = JSON.createObjectNode();
        content.put("msgtype", "m.text");
        content.put("body", body);
        return sendContent(roomId, content, mentionIds, kind, taskId, originUserId);
    }

    /**
     * 发送媒体消息（m.image/m.file/m.audio/m.video）。url 为 upload 返回的 mxc:// 地址，
     * info 携带 mimetype/size/宽高/时长等 Matrix 媒体元信息。
     */
    public String sendMedia(String roomId, String msgtype, String url, String body, ObjectNode info,
                            List<String> mentionIds, String kind, String taskId) {
        return sendMedia(roomId, msgtype, url, body, info, mentionIds, kind, taskId, null);
    }

    public String sendMedia(String roomId, String msgtype, String url, String body, ObjectNode info,
                            List<String> mentionIds, String kind, String taskId, String originUserId) {
        ObjectNode content = JSON.createObjectNode();
        content.put("msgtype", msgtype);
        content.put("body", body);
        content.put("url", url);
        if (info != null) {
            content.set("info", info);
        }
        return sendContent(roomId, content, mentionIds, kind, taskId, originUserId);
    }

    private String sendContent(String roomId, ObjectNode content, List<String> mentionIds, String kind, String taskId,
                               String originUserId) {
        if (mentionIds != null && !mentionIds.isEmpty()) {
            ArrayNode users = content.putObject("m.mentions").putArray("user_ids");
            mentionIds.forEach(users::add);
        }
        boolean hasKind = kind != null && !kind.isBlank();
        boolean hasOrigin = originUserId != null && !originUserId.isBlank();
        if (hasKind || hasOrigin) {
            ObjectNode team = content.putObject(TeamProtocol.CONTENT_KEY);
            if (hasKind) {
                team.put("kind", kind);
                if (taskId != null && !taskId.isBlank()) {
                    team.put("taskId", taskId);
                }
            }
            if (hasOrigin) {
                team.put("userId", originUserId);
            }
        }
        String txnId = UUID.randomUUID().toString();
        String path = "/_matrix/client/v3/rooms/" + encode(roomId)
                + "/send/m.room.message/" + encode(txnId);
        JsonNode response = request("PUT", path, content, Duration.ofSeconds(30));
        return response.path("event_id").asText();
    }

    /** 发送任意类型的房间事件（如执行过程快照 com.h.agent.progress）。 */
    public String sendEvent(String roomId, String eventType, ObjectNode content) {
        String txnId = UUID.randomUUID().toString();
        String path = "/_matrix/client/v3/rooms/" + encode(roomId)
                + "/send/" + encode(eventType) + "/" + encode(txnId);
        JsonNode response = request("PUT", path, content, Duration.ofSeconds(30));
        return response.path("event_id").asText();
    }

    /** 公开已读回执（m.read）：标记本账号已读到该事件，供房间内其他成员展示已读状态。 */
    public void sendReadReceipt(String roomId, String eventId) {
        String path = "/_matrix/client/v3/rooms/" + encode(roomId)
                + "/receipt/m.read/" + encode(eventId);
        request("POST", path, JSON.createObjectNode(), Duration.ofSeconds(15));
    }

    /** 上传媒体到 homeserver，返回 mxc:// 地址。 */
    public String upload(String filename, String contentType, byte[] data) {
        String path = "/_matrix/media/v3/upload?filename=" + encode(filename);
        JsonNode response = requestRaw("POST", path, HttpRequest.BodyPublishers.ofByteArray(data),
                contentType, Duration.ofSeconds(120));
        String uri = response.path("content_uri").asText("");
        if (uri.isBlank()) {
            throw new IllegalStateException("Matrix upload failed for " + filename + ": " + response);
        }
        return uri;
    }

    /** 下载 mxc:// 媒体内容。 */
    public byte[] download(String mxcUrl) {
        if (mxcUrl == null || !mxcUrl.startsWith("mxc://")) {
            throw new IllegalArgumentException("Not an mxc url: " + mxcUrl);
        }
        String rest = mxcUrl.substring("mxc://".length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) {
            throw new IllegalArgumentException("Malformed mxc url: " + mxcUrl);
        }
        // Synapse 1.159 已下线 legacy 下载端点（404），鉴权媒体走 client/v1；上传仍保留 media/v3。
        String path = "/_matrix/client/v1/media/download/" + encode(rest.substring(0, slash))
                + "/" + encode(rest.substring(slash + 1));
        try {
            HttpRequest request = builder("GET", path, HttpRequest.BodyPublishers.noBody(),
                    "application/octet-stream", Duration.ofSeconds(120)).build();
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Matrix GET " + path + " -> " + response.statusCode()
                        + " " + new String(response.body(), StandardCharsets.UTF_8));
            }
            return response.body();
        } catch (IOException e) {
            throw new IllegalStateException("Matrix request failed: GET " + path, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Matrix request interrupted: GET " + path, e);
        }
    }

    private JsonNode request(String method, String path, JsonNode body, Duration timeout) {
        try {
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
            return requestRaw(method, path, publisher, "application/json", timeout);
        } catch (IOException e) {
            throw new IllegalStateException("Matrix request serialize failed: " + method + " " + path, e);
        }
    }

    private JsonNode requestRaw(String method, String path, HttpRequest.BodyPublisher publisher,
                                String contentType, Duration timeout) {
        try {
            HttpRequest request = builder(method, path, publisher, contentType, timeout).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
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

    private HttpRequest.Builder builder(String method, String path, HttpRequest.BodyPublisher publisher,
                                        String contentType, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + path))
                .timeout(timeout)
                .header("Content-Type", contentType);
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return builder.method(method, publisher);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}