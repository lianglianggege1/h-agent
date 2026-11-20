package com.h.matrixteam;

import java.util.List;

/**
 * 房间内一条产品消息。kind/taskId/originUserId 来自 com.h.team 团队元数据；
 * 人类消息没有团队元数据，kind 与 originUserId 为 null（归属按真实 sender 解析）。
 * originUserId 为委派/回报/提问链背后的工作台用户 id，bot 据此解析记忆/资产归属。
 * 媒体消息（图片/文件/音频/视频）带 msgtype/url/mimetype/size，文本消息 url 为 null。
 */
public record TeamMessage(
        String eventId,
        String roomId,
        String sender,
        String body,
        List<String> mentions,
        boolean roomMention,
        String kind,
        String taskId,
        String msgtype,
        String url,
        String mimetype,
        long size,
        String originUserId
) {
    public TeamMessage(String eventId, String roomId, String sender, String body,
                       List<String> mentions, boolean roomMention, String kind, String taskId) {
        this(eventId, roomId, sender, body, mentions, roomMention, kind, taskId, "m.text", null, null, 0, null);
    }

    public boolean mentions(String userId) {
        return roomMention || mentions.contains(userId);
    }

    public boolean isMedia() {
        return TeamProtocol.isMedia(msgtype);
    }
}