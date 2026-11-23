package com.h.matrixteam;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 框架无关的团队协作文档：社交上下文感知、跨房间历史、成员互通、跨房间分享。
 * 当前房间由调用方（各框架工具入口）解析后传入；本服务不依赖任何上层模块。
 * 是否调用、发给谁、分享到哪由 Agent 自主判断，但所有跨房间能力都受调用方传入的
 * roomAllowed 谓词约束——该谓词按本轮归属用户判定房间访问权（同一用户可跨自己的房间，
 * 不能触达他人房间），枚举/读取/发送/成员解析一律先过谓词，越权直接拒绝。
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

    /** 列出归属用户可访问的房间（群聊/单聊）、名称、成员与角色，并标记当前房间。 */
    public String listRooms(String currentRoomId, Predicate<String> roomAllowed) {
        List<String> lines = new ArrayList<>();
        for (JsonNode room : client.joinedRooms().path("joined_rooms")) {
            String roomId = room.asText();
            if (!allowed(roomId, currentRoomId, roomAllowed)) {
                continue;
            }
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
            return "（没有可访问的房间）";
        }
        return "我加入的房间：\n" + String.join("\n", lines);
    }

    /** 翻阅指定房间历史；目标房间须在归属用户的可访问范围内，否则拒绝。 */
    public String readRoom(String roomId, Integer limit, String before, Predicate<String> roomAllowed) {
        if (roomId == null || roomId.isBlank()) {
            return "缺少目标房间，未读取。";
        }
        if (!roomAllowed.test(roomId)) {
            return denied(roomId);
        }
        return history.read(roomId, limit, before);
    }

    /**
     * 向另一成员发消息：direct=true 时建立/复用单聊，否则在指定房间（缺省当前房间）内点名对方。
     * 目标可用名字（worker/spring-worker/manager/human）或完整 userId，但只能解析到归属用户可访问房间内的成员；
     * 群内发送的目标房间也须在可访问范围内。originUserId 写入 com.h.team.userId 延续归属链。
     */
    public String messageMember(String currentRoomId, String target, String text, String roomId, boolean direct,
                                String originUserId, Predicate<String> roomAllowed) {
        String userId = resolveUser(target, roomAllowed);
        if (userId.equals(client.userId())) {
            return "不能给自己发消息。";
        }
        String body = text == null ? "" : text.strip();
        if (body.isEmpty()) {
            return "消息正文为空，未发送。";
        }
        String targetRoom;
        if (direct) {
            targetRoom = findOrCreateDm(userId);
        } else {
            targetRoom = roomId == null || roomId.isBlank() ? currentRoomId : roomId;
            if (!allowed(targetRoom, currentRoomId, roomAllowed)) {
                return denied(targetRoom);
            }
        }
        client.sendText(targetRoom, body, List.of(userId), null, null, originUserId);
        return "已向 " + target + " 发送消息（房间 " + targetRoom + (direct ? "，单聊" : "") + "），等待对方回应。";
    }

    /** 把文本或媒体（mxc://）分享到另一房间；目标房间须在归属用户的可访问范围内。 */
    public String shareToRoom(String targetRoomId, String text, String mxcUrl, String filename, String msgtype,
                              String originUserId, Predicate<String> roomAllowed) {
        if (targetRoomId == null || targetRoomId.isBlank()) {
            return "缺少目标房间，未分享。可先用 list_my_rooms 查看房间 ID。";
        }
        if (!roomAllowed.test(targetRoomId)) {
            return denied(targetRoomId);
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

    /**
     * 名字或 userId → 完整 userId；先查名册（团队 bot），再仅在归属用户可访问的房间内扫描成员匹配
     * localpart/显示名。完整 userId 若不在名册也须为某可访问房间的已加入成员，否则视为未知成员。
     */
    public String resolveUser(String target, Predicate<String> roomAllowed) {
        if (target == null || target.isBlank()) {
            throw new IllegalArgumentException("缺少目标成员");
        }
        String raw = target.strip();
        String key = raw.startsWith("@") ? raw.substring(1) : raw;
        int colon = key.indexOf(':');
        if (colon > 0) {
            key = key.substring(0, colon);
        }
        String userId = roster.get(key);
        if (userId != null) {
            return userId;
        }
        boolean fullUserId = raw.startsWith("@") && raw.contains(":");
        for (JsonNode room : client.joinedRooms().path("joined_rooms")) {
            String roomId = room.asText();
            if (!roomAllowed.test(roomId)) {
                continue;
            }
            for (JsonNode event : client.roomMembers(roomId).path("chunk")) {
                if (!"join".equals(event.path("content").path("membership").asText())) {
                    continue;
                }
                String member = event.path("state_key").asText();
                if (fullUserId) {
                    if (member.equals(raw)) {
                        return member;
                    }
                    continue;
                }
                String display = event.path("content").path("displayname").asText("");
                if (localpart(member).equalsIgnoreCase(key) || display.equalsIgnoreCase(raw)) {
                    return member;
                }
            }
        }
        throw new IllegalArgumentException("未知成员：" + target + "，可用 list_my_rooms 查看成员");
    }

    /** 当前房间恒允许（本轮正在其中执行）；其余房间按归属谓词判定。 */
    private static boolean allowed(String roomId, String currentRoomId, Predicate<String> roomAllowed) {
        return roomId != null && (roomId.equals(currentRoomId) || roomAllowed.test(roomId));
    }

    private static String denied(String roomId) {
        return "无权访问房间 " + roomId + "：它不属于当前用户。可用 list_my_rooms 查看可访问的房间。";
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