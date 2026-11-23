package com.h.matrixteam;

import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Manager–Worker 触发策略。人类消息默认由 Manager 接待；
 * Worker 只认结构化点名（委派与点名都带 m.mentions），多 Worker 房间不会抢答同一条委派；
 * 确认、旁听与自发消息不触发执行。
 * 团队房间由 isTeamRoom 谓词判定（每用户专属团队房间皆视为团队群聊），其余房间按单聊规则路由。
 */
public final class TeamPolicy {

    public enum Role { MANAGER, WORKER }

    private final Role role;
    private final String selfUserId;
    private final String managerUserId;
    private final Predicate<String> isTeamRoom;
    private final Set<String> agentUserIds;

    /** 单一团队房间便捷构造（测试用）；房间相等即视为团队房间。 */
    public TeamPolicy(Role role, String selfUserId, String managerUserId, String teamRoomId, String... workerUserIds) {
        this(role, selfUserId, managerUserId, roomId -> roomId != null && roomId.equals(teamRoomId), workerUserIds);
    }

    /** 多团队房间构造：isTeamRoom 判定某房间是否按团队群聊规则路由（每用户独立房间场景）。 */
    public TeamPolicy(Role role, String selfUserId, String managerUserId, Predicate<String> isTeamRoom,
                      String... workerUserIds) {
        this.role = role;
        this.selfUserId = selfUserId;
        this.managerUserId = managerUserId;
        this.isTeamRoom = isTeamRoom;
        Set<String> agents = new HashSet<>();
        agents.add(selfUserId);
        agents.add(managerUserId);
        agents.addAll(Set.of(workerUserIds));
        this.agentUserIds = Set.copyOf(agents);
    }

    public boolean isAgent(String sender) {
        return agentUserIds.contains(sender);
    }

    /** 历史翻阅用的发言人身份标签：自己、Manager、Worker（localpart）、Human（localpart）。 */
    public String speakerLabel(String sender) {
        if (sender.equals(selfUserId)) {
            return "自己（" + roleName(role) + "）";
        }
        if (sender.equals(managerUserId)) {
            return "Manager";
        }
        if (isAgent(sender)) {
            return "Worker（" + localpart(sender) + "）";
        }
        return "Human（" + localpart(sender) + "）";
    }

    private static String roleName(Role role) {
        return role == Role.MANAGER ? "Manager" : "Worker";
    }

    private static String localpart(String userId) {
        int colon = userId.indexOf(':');
        return userId.substring(userId.startsWith("@") ? 1 : 0, colon < 0 ? userId.length() : colon);
    }

    public boolean shouldRespond(TeamMessage message) {
        String sender = message.sender();
        if (sender.equals(selfUserId)) {
            return false;
        }
        boolean fromHuman = !isAgent(sender);
        // 单聊房间（非团队群）：人类消息无需点名即触发；成员互通时，被点名的 agent 消息也触发。
        if (!isTeamRoom.test(message.roomId())) {
            return fromHuman || message.mentions(selfUserId);
        }
        return switch (role) {
            case MANAGER -> fromHuman
                    // 未点名默认接待；点名必须包含 Manager，只点 Worker 的消息不抢答。
                    ? (!hasMentions(message) || message.mentions(selfUserId))
                    // Worker 的回报/提问必须处理；其余发言仅在明确点名 Manager 时处理。
                    : (TeamProtocol.KIND_REPORT.equals(message.kind())
                        || TeamProtocol.KIND_QUESTION.equals(message.kind())
                        || message.mentions(selfUserId));
            // 委派消息必然点名目标 Worker，只认点名即可避免多个 Worker 抢答同一条委派。
            case WORKER -> message.mentions(selfUserId);
        };
    }

    private static boolean hasMentions(TeamMessage message) {
        return message.roomMention() || !message.mentions().isEmpty();
    }
}