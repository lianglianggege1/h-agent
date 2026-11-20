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

    public static final String MSG_IMAGE = "m.image";
    public static final String MSG_FILE = "m.file";
    public static final String MSG_AUDIO = "m.audio";
    public static final String MSG_VIDEO = "m.video";

    private TeamProtocol() {
    }

    public static boolean isMedia(String msgtype) {
        return MSG_IMAGE.equals(msgtype) || MSG_FILE.equals(msgtype)
                || MSG_AUDIO.equals(msgtype) || MSG_VIDEO.equals(msgtype);
    }

    /** 解析 m.room.message 的文本与媒体事件；其他事件返回 null。 */
    public static TeamMessage parse(String roomId, JsonNode event) {
        if (!"m.room.message".equals(event.path("type").asText())) {
            return null;
        }
        JsonNode content = event.path("content");
        String msgtype = content.path("msgtype").asText("");
        boolean media = isMedia(msgtype);
        if (!"m.text".equals(msgtype) && !media) {
            return null;
        }
        String url = media ? content.path("url").asText(null) : null;
        String body = content.path("body").asText("");
        if (body.isBlank()) {
            if (!media) {
                return null;
            }
            body = url != null && url.contains("/") ? url.substring(url.lastIndexOf('/') + 1) : "附件";
        }
        List<String> mentions = new ArrayList<>();
        for (JsonNode id : content.path("m.mentions").path("user_ids")) {
            mentions.add(id.asText());
        }
        boolean roomMention = content.path("m.mentions").path("room").asBoolean(false);
        JsonNode team = content.path(CONTENT_KEY);
        JsonNode info = content.path("info");
        return new TeamMessage(
                event.path("event_id").asText(),
                roomId,
                event.path("sender").asText(),
                body,
                List.copyOf(mentions),
                roomMention,
                team.path("kind").asText(null),
                team.path("taskId").asText(null),
                msgtype,
                url,
                info.path("mimetype").asText(null),
                info.path("size").asLong(0),
                team.path("userId").asText(null)
        );
    }
}