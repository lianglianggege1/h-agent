package com.h.matrixteam;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 框架无关的团队协作文档：社交上下文感知、跨房间历史、成员互通、跨房间分享。
 * 当前房间由调用方（各框架工具入口）解析后传入；本服务不依赖任何上层模块。
 * 是否调用、发给谁、分享到哪由 Agent 自主判断；发送范围天然受 Matrix 房间成员身份约束。
 */
public final class TeamCollaboration {

    private final MatrixClient client;
    private final TeamPolicy policy;
    private final TeamHistoryReader history;
    private final Map<String, String> roster;

    public TeamCollaboration(MatrixClient client, TeamPolicy policy, String teamRoomId, Map<String, String> roster) {
        this.client = client;
        this.policy = policy;
        this.history = new TeamHistoryReader(client, teamRoomId, policy);
        this.roster = Map.copyOf(roster);
    }

    /** 列出我加入的房间（群聊/单聊）、名称、成员与角色，并标记当前房间。 */
    public String listRooms(String currentRoomId) {
        List<String> lines = new ArrayList<>();
        for (JsonNode room : client.joinedRooms().path("joined_rooms")) {
            String roomId = room.asText();
            List<String> members = joinedMembers(roomId);
            String name = client.roomName(roomId);
            String title = name == null ? roomId : name;
            String type = members.size() > 2 ? "群聊" : "单聊";
            String mark = roomId.equals(currentRoomId) ? "（当前）" : "";
            List<String> labels = new ArrayList<>();
            for (String userId : members) {
                labels.add(policy.speakerLabel(userId));
            }
            lines.add("- " + type + mark + " " + title + " [" + roomId + "] 成员：" + String.join("、", labels));
        }
        if (lines.isEmpty()) {
            return "（没有加入任何房间）";
        }
        return "我加入的房间：\n" + String.join("\n", lines);
    }

    /** 翻阅指定房间历史（默认当前房间由调用方传入）。 */
    public String readRoom(String roomId, Integer limit, String before) {
        return history.read(roomId, limit, before);
    }

    /**
     * 向另一成员发消息：direct=true 时建立/复用单聊，否则在指定房间（缺省当前房间）内点名对方。
     * 目标可用名字（worker/spring-worker/manager/human）或完整 userId。
     */
    public String messageMember(String currentRoomId, String target, String text, String roomId, boolean direct) {
        return messageMember(currentRoomId, target, text, roomId, direct, null);
    }

    /** originUserId 为本轮归属的工作台用户 id，写入 com.h.team.userId 以延续归属链。 */
    public String messageMember(String currentRoomId, String target, String text, String roomId, boolean direct,
                                String originUserId) {
        String userId = resolveUser(target);
        if (userId.equals(client.userId())) {
            return "不能给自己发消息。";
        }
        String body = text == null ? "" : text.strip();
        if (body.isEmpty()) {
            return "消息正文为空，未发送。";
        }
        String targetRoom = direct ? findOrCreateDm(userId)
                : (roomId == null || roomId.isBlank() ? currentRoomId : roomId);
        client.sendText(targetRoom, body, List.of(userId), null, null, originUserId);
        return "已向 " + target + " 发送消息（房间 " + targetRoom + (direct ? "，单聊" : "") + "），等待对方回应。";
    }

    /** 把文本或媒体（mxc://）分享到另一房间，实现跨房间互通有无。 */
    public String shareToRoom(String targetRoomId, String text, String mxcUrl, String filename, String msgtype) {
        return shareToRoom(targetRoomId, text, mxcUrl, filename, msgtype, null);
    }

    public String shareToRoom(String targetRoomId, String text, String mxcUrl, String filename, String msgtype,
                              String originUserId) {
        if (targetRoomId == null || targetRoomId.isBlank()) {
            return "缺少目标房间，未分享。可先用 list_my_rooms 查看房间 ID。";
        }
        if (mxcUrl != null && mxcUrl.startsWith("mxc://")) {
            String type = msgtype == null || msgtype.isBlank() ? guessMsgType(filename) : msgtype;
            String body = filename != null && !filename.isBlank() ? filename
                    : (text != null && !text.isBlank() ? text : "分享文件");
            client.sendMedia(targetRoomId, type, mxcUrl, body, null, List.of(), null, null, originUserId);
            return "已把媒体分享到房间 " + targetRoomId + "。";
        }
        if (text != null && !text.isBlank()) {
            client.sendText(targetRoomId, text.strip(), List.of(), null, null, originUserId);
            return "已把文本分享到房间 " + targetRoomId + "。";
        }
        return "没有可分享的内容（提供 text 或 mxcUrl）。";
    }

    /** 名字或 userId → 完整 userId；先查名册，再扫已加入房间成员匹配 localpart/显示名。 */
    public String resolveUser(String target) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("缺少目标成员");
        }
        String raw = target.strip();
        if (raw.startsWith("@") && raw.contains(":")) {
            return raw;
        }
        String key = raw.startsWith("@") ? raw.substring(1) : raw;
        int colon = key.indexOf(':');
        if (colon > 0) {
            key = key.substring(0, colon);
        }
        String userId = roster.get(key);
        if (userId != null) {
            return userId;
        }
        for (JsonNode room : client.joinedRooms().path("joined_rooms")) {
            for (JsonNode event : client.roomMembers(room.asText()).path("chunk")) {
                if (!"join".equals(event.path("content").path("membership").asText())) {
                    continue;
                }
                String member = event.path("state_key").asText();
                String display = event.path("content").path("displayname").asText("");
                if (localpart(member).equalsIgnoreCase(key) || display.equalsIgnoreCase(raw)) {
                    return member;
                }
            }
        }
        throw new IllegalArgumentException("未知成员：" + target + "，可用 list_my_rooms 查看成员");
    }

    private String findOrCreateDm(String userId) {
        for (JsonNode room : client.joinedRooms().path("joined_rooms")) {
            String roomId = room.asText();
            List<String> members = joinedMembers(roomId);
            if (members.size() == 2 && members.contains(userId) && members.contains(client.userId())) {
                return roomId;
            }
        }
        String roomId = client.createDm(userId);
        waitForJoin(roomId, userId);
        return roomId;
    }

    private void waitForJoin(String roomId, String userId) {
        for (int attempt = 0; attempt < 15; attempt++) {
            if (joinedMembers(roomId).contains(userId)) {
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private List<String> joinedMembers(String roomId) {
        List<String> members = new ArrayList<>();
        for (JsonNode event : client.roomMembers(roomId).path("chunk")) {
            if ("join".equals(event.path("content").path("membership").asText())) {
                members.add(event.path("state_key").asText());
            }
        }
        return members;
    }

    private static String guessMsgType(String filename) {
        if (filename == null) {
            return "m.file";
        }
        String name = filename.toLowerCase();
        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                || name.endsWith(".gif") || name.endsWith(".webp")) {
            return "m.image";
        }
        if (name.endsWith(".mp4") || name.endsWith(".mov") || name.endsWith(".webm")) {
            return "m.video";
        }
        if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".ogg")) {
            return "m.audio";
        }
        return "m.file";
    }

    private static String localpart(String userId) {
        int colon = userId.indexOf(':');
        return userId.substring(userId.startsWith("@") ? 1 : 0, colon < 0 ? userId.length() : colon);
    }

    /** 由已知 agent userId 构建 localpart→userId 名册（人类成员经房间成员解析）。 */
    public static Map<String, String> rosterOf(String... userIds) {
        Map<String, String> roster = new HashMap<>();
        for (String userId : userIds) {
            if (userId != null && !userId.isBlank()) {
                roster.put(localpart(userId), userId);
            }
        }
        return roster;
    }
}