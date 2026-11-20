package com.h.matrixteam;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 房间历史翻阅：/messages 反向分页取事件，输出按时间正序、带发言人身份标签的文本。
 * 身份标签由 TeamPolicy 提供（自己/对端 Agent/Human），供 Agent 自主决定何时调用。
 */
public final class TeamHistoryReader {

    public static final int MAX_LIMIT = 50;
    public static final int DEFAULT_LIMIT = 20;
    private static final int BODY_CAP = 800;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm");

    private final MatrixClient client;
    private final String roomId;
    private final TeamPolicy policy;

    public TeamHistoryReader(MatrixClient client, String roomId, TeamPolicy policy) {
        this.client = client;
        this.roomId = roomId;
        this.policy = policy;
    }

    public String read(Integer limit, String before) {
        return read(roomId, limit, before);
    }

    /** 翻阅指定房间（须为本账号已加入的房间）的历史；roomId 缺省由调用方解析为当前房间。 */
    public String read(String targetRoomId, Integer limit, String before) {
        int size = limit == null ? DEFAULT_LIMIT : Math.clamp(limit, 1, MAX_LIMIT);
        JsonNode response = client.messages(targetRoomId, size, before);
        StringBuilder text = new StringBuilder(format(response, targetRoomId, policy));
        String end = response.path("end").asText(null);
        if (end != null && !end.isBlank() && !response.path("chunk").isEmpty()) {
            text.append("\n[更早历史翻页令牌: ").append(end).append("]");
        }
        return text.toString();
    }

    static String format(JsonNode response, String roomId, TeamPolicy policy) {
        List<String> lines = new ArrayList<>();
        for (JsonNode event : response.path("chunk")) {
            TeamMessage message = TeamProtocol.parse(roomId, event);
            if (message == null) {
                continue;
            }
            String time = TIME.format(Instant.ofEpochMilli(event.path("origin_server_ts").asLong(0))
                    .atZone(ZoneId.systemDefault()));
            StringBuilder line = new StringBuilder("[").append(time).append("] ")
                    .append(policy.speakerLabel(message.sender()));
            if (message.kind() != null) {
                line.append("（").append(kindLabel(message.kind()));
                if (message.taskId() != null) {
                    line.append(" ").append(message.taskId());
                }
                line.append("）");
            }
            String body = message.body();
            if (message.isMedia()) {
                body = mediaLabel(message.msgtype()) + " " + body + mediaMeta(message)
                        + (message.url() != null ? " " + message.url() : "");
            }
            if (body.length() > BODY_CAP) {
                body = body.substring(0, BODY_CAP) + "…（已截断）";
            }
            lines.add(line.append(": ").append(body).toString());
        }
        if (lines.isEmpty()) {
            return "（该范围内没有历史消息）";
        }
        Collections.reverse(lines);
        return String.join("\n", lines);
    }

    private static String mediaLabel(String msgtype) {
        return switch (msgtype) {
            case TeamProtocol.MSG_IMAGE -> "[图片]";
            case TeamProtocol.MSG_FILE -> "[文件]";
            case TeamProtocol.MSG_AUDIO -> "[音频]";
            case TeamProtocol.MSG_VIDEO -> "[视频]";
            default -> "[媒体]";
        };
    }

    private static String mediaMeta(TeamMessage message) {
        List<String> parts = new ArrayList<>();
        if (message.mimetype() != null && !message.mimetype().isBlank()) {
            parts.add(message.mimetype());
        }
        if (message.size() > 0) {
            parts.add(humanSize(message.size()));
        }
        return parts.isEmpty() ? "" : "（" + String.join(", ", parts) + "）";
    }

    private static String humanSize(long size) {
        if (size < 1024) {
            return size + "B";
        }
        if (size < 1024 * 1024) {
            return String.format("%.1fKB", size / 1024.0);
        }
        return String.format("%.1fMB", size / (1024.0 * 1024));
    }

    private static String kindLabel(String kind) {
        return switch (kind) {
            case TeamProtocol.KIND_DELEGATE -> "委派";
            case TeamProtocol.KIND_REPORT -> "回报";
            case TeamProtocol.KIND_QUESTION -> "提问";
            default -> kind;
        };
    }
}