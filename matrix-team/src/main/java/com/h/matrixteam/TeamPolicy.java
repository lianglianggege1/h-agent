package com.h.matrixteam;

import java.util.Set;

/**
 * Manager–Worker 触发策略。人类消息默认由 Manager 接待；
 * Worker 只在被委派或被明确提及时行动；确认、旁听与自发消息不触发执行。
 */
public final class TeamPolicy {

    public enum Role { MANAGER, WORKER }

    private final Role role;
    private final String selfUserId;
    private final String peerAgentUserId;
    private final Set<String> agentUserIds;

    public TeamPolicy(Role role, String selfUserId, String peerAgentUserId) {
        this.role = role;
        this.selfUserId = selfUserId;
        this.peerAgentUserId = peerAgentUserId;
        this.agentUserIds = Set.of(selfUserId, peerAgentUserId);
    }

    public boolean isAgent(String sender) {
        return agentUserIds.contains(sender);
    }

    public boolean shouldRespond(TeamMessage message) {
        String sender = message.sender();
        if (sender.equals(selfUserId)) {
            return false;
        }
        boolean fromHuman = !isAgent(sender);
        return switch (role) {
            case MANAGER -> fromHuman
                    // 未点名默认接待；点名必须包含 Manager，只点 Worker 的消息不抢答。
                    ? (!hasMentions(message) || message.mentions(selfUserId))
                    // Worker 的回报/提问必须处理；其余发言仅在明确点名 Manager 时处理。
                    : (TeamProtocol.KIND_REPORT.equals(message.kind())
                        || TeamProtocol.KIND_QUESTION.equals(message.kind())
                        || message.mentions(selfUserId));
            case WORKER -> fromHuman
                    ? message.mentions(selfUserId)
                    : (TeamProtocol.KIND_DELEGATE.equals(message.kind())
                        || message.mentions(selfUserId));
        };
    }

    private static boolean hasMentions(TeamMessage message) {
        return message.roomMention() || !message.mentions().isEmpty();
    }
}