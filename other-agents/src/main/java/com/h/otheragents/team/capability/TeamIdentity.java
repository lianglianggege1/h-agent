package com.h.otheragents.team.capability;

import com.h.backend.capability.matrix.MatrixConversationAssetAdapter;
import com.h.backend.memory.application.AgentMemoryContext;
import com.h.backend.memory.domain.MemoryInvocationContext;

/**
 * 团队执行身份：memoryId 采用 legacy 四段式 {humanUserId}:agent:{agentId}:{roomId}。
 * roomId 本身含冒号（!xxx:matrix.local），split(":",4) 的末段保留完整 roomId，
 * ChatMemoryIdFactory 与 FileDeliveryTool 均按此格式解析出 userId 与 sessionId(=roomId)。
 * 能力数据归属人类工作台账号（团队共享同一人类 Matrix 身份）。
 */
public final class TeamIdentity {

    private TeamIdentity() {
    }

    public static String memoryId(long humanUserId, String agentId, String roomId) {
        return humanUserId + ":agent:" + agentId + ":" + roomId;
    }

    public static AgentMemoryContext agentContext(long userId, String roomId, String agentId, String eventId) {
        return new AgentMemoryContext(userId, roomId, roomId, agentId, agentId, null, eventId);
    }

    public static MemoryInvocationContext memoryContext(long userId, String roomId, String agentId, String eventId) {
        return new MemoryInvocationContext(
                userId, agentId, eventId,
                MatrixConversationAssetAdapter.eventHash(eventId), roomId, null);
    }
}