package com.h.matrixteam;

import java.util.List;

/**
 * 房间内一条产品消息。kind/taskId 来自 com.h.team 团队元数据；
 * 人类消息没有团队元数据，kind 为 null。
 */
public record TeamMessage(
        String eventId,
        String roomId,
        String sender,
        String body,
        List<String> mentions,
        boolean roomMention,
        String kind,
        String taskId
) {
    public boolean mentions(String userId) {
        return roomMention || mentions.contains(userId);
    }
}