package com.h.matrixteam;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * 团队消息协议：委派/回报等协作语义放在 content 的 com.h.team 字段，
 * 触发判定依赖结构化 m.mentions，而不是正文里出现名字。
 */
public final class TeamProtocol {

    public static final String CONTENT_KEY = "com.h.team";

    public static final String KIND_DELEGATE = "delegate";
    public static final String KIND_REPORT = "report";
    public static final String KIND_QUESTION = "question";

    private TeamProtocol() {
    }

    /** 只解析 m.room.message 文本事件；其他事件返回 null。 */
    public static TeamMessage parse(String roomId, JsonNode event) {
        if (!"m.room.message".equals(event.path("type").asText())) {
            return null;
        }
        JsonNode content = event.path("content");
        if (!"m.text".equals(content.path("msgtype").asText())) {
            return null;
        }
        String body = content.path("body").asText("");
        if (body.isBlank()) {
            return null;
        }
        List<String> mentions = new ArrayList<>();
        for (JsonNode id : content.path("m.mentions").path("user_ids")) {
            mentions.add(id.asText());
        }
        boolean roomMention = content.path("m.mentions").path("room").asBoolean(false);
        JsonNode team = content.path(CONTENT_KEY);
        return new TeamMessage(
                event.path("event_id").asText(),
                roomId,
                event.path("sender").asText(),
                body,
                List.copyOf(mentions),
                roomMention,
                team.path("kind").asText(null),
                team.path("taskId").asText(null)
        );
    }
}