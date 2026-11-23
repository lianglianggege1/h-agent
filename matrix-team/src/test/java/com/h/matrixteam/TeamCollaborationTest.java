package com.h.matrixteam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TeamCollaborationTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MANAGER = "@manager:matrix.local";
    private static final String WORKER = "@worker:matrix.local";
    private static final String SPRING_WORKER = "@spring-worker:matrix.local";
    private static final String HUMAN = "@huajiang:matrix.local";
    private static final String OTHER_HUMAN = "@intruder:matrix.local";
    private static final String ROOM = "!room:matrix.local";
    private static final String DM = "!dm:matrix.local";
    private static final String FOREIGN = "!foreign:matrix.local";

    /** 功能用例沿用「全部允许」谓词；归属过滤由专门的授权用例覆盖。 */
    private static final Predicate<String> ALLOW_ALL = roomId -> true;

    private MatrixClient client;
    private TeamCollaboration collaboration;

    @BeforeEach
    void setUp() {
        client = mock(MatrixClient.class);
        when(client.userId()).thenReturn(WORKER);
        TeamPolicy policy = new TeamPolicy(TeamPolicy.Role.WORKER, WORKER, MANAGER, ROOM, WORKER, SPRING_WORKER);
        collaboration = new TeamCollaboration(client, policy, ROOM,
                TeamCollaboration.rosterOf(MANAGER, WORKER, SPRING_WORKER));
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String member(String userId, String displayname) {
        return "{\"state_key\":\"" + userId + "\",\"content\":{\"membership\":\"join\",\"displayname\":\""
                + displayname + "\"}}";
    }

    private void stubRoom(String roomId, String name, String... members) {
        StringBuilder chunk = new StringBuilder();
        for (String userId : members) {
            if (!chunk.isEmpty()) {
                chunk.append(',');
            }
            chunk.append(member(userId, userId.substring(1, userId.indexOf(':'))));
        }
        when(client.roomMembers(roomId)).thenReturn(json("{\"chunk\":[" + chunk + "]}"));
        when(client.roomName(roomId)).thenReturn(name);
    }

    @Test
    void listRoomsMarksCurrentRoomAndDistinguishesGroupFromDm() {
        when(client.joinedRooms()).thenReturn(json("{\"joined_rooms\":[\"" + ROOM + "\",\"" + DM + "\"]}"));
        stubRoom(ROOM, "团队群", HUMAN, MANAGER, WORKER, SPRING_WORKER);
        stubRoom(DM, null, WORKER, SPRING_WORKER);
        String text = collaboration.listRooms(ROOM, ALLOW_ALL);
        assertTrue(text.contains("群聊（当前） 团队群 [" + ROOM + "]"), text);
        assertTrue(text.contains("单聊 " + DM + " [" + DM + "]"), text);
        assertTrue(text.contains("自己（Worker）"), text);
        assertTrue(text.contains("Manager"), text);
        assertTrue(text.contains("Human（huajiang）"), text);
    }

    @Test
    void resolveUserByNameUserIdAndRoomScan() {
        assertEquals(MANAGER, collaboration.resolveUser("manager", ALLOW_ALL));
        assertEquals(SPRING_WORKER, collaboration.resolveUser("@spring-worker:matrix.local", ALLOW_ALL));
        assertEquals(SPRING_WORKER, collaboration.resolveUser("spring-worker", ALLOW_ALL));
        when(client.joinedRooms()).thenReturn(json("{\"joined_rooms\":[\"" + ROOM + "\"]}"));
        stubRoom(ROOM, "团队群", HUMAN, MANAGER, WORKER, SPRING_WORKER);
        assertEquals(HUMAN, collaboration.resolveUser("huajiang", ALLOW_ALL));
        assertThrows(IllegalArgumentException.class, () -> collaboration.resolveUser("nobody", ALLOW_ALL));
    }

    @Test
    void messageMemberInRoomMentionsTarget() {
        String result = collaboration.messageMember(ROOM, "spring-worker", "帮我查个资料", null, false, null, ALLOW_ALL);
        verify(client).sendText(ROOM, "帮我查个资料", List.of(SPRING_WORKER), null, null, null);
        assertTrue(result.contains("已向 spring-worker 发送消息"), result);
    }

    @Test
    void messageMemberDirectReusesExistingDm() {
        when(client.joinedRooms()).thenReturn(json("{\"joined_rooms\":[\"" + ROOM + "\",\"" + DM + "\"]}"));
        stubRoom(ROOM, "团队群", HUMAN, MANAGER, WORKER, SPRING_WORKER);
        stubRoom(DM, null, WORKER, SPRING_WORKER);
        collaboration.messageMember(ROOM, "spring-worker", "私下说", null, true, null, ALLOW_ALL);
        verify(client).sendText(DM, "私下说", List.of(SPRING_WORKER), null, null, null);
        verify(client, never()).createDm(anyString());
    }

    @Test
    void messageMemberDirectCreatesDmWhenNoneExists() {
        String newDm = "!newdm:matrix.local";
        when(client.joinedRooms()).thenReturn(json("{\"joined_rooms\":[\"" + ROOM + "\"]}"));
        stubRoom(ROOM, "团队群", HUMAN, MANAGER, WORKER, SPRING_WORKER);
        when(client.createDm(MANAGER)).thenReturn(newDm);
        stubRoom(newDm, null, WORKER, MANAGER);
        collaboration.messageMember(ROOM, "manager", "同步一下进度", null, true, null, ALLOW_ALL);
        verify(client).createDm(MANAGER);
        verify(client).sendText(newDm, "同步一下进度", List.of(MANAGER), null, null, null);
    }

    @Test
    void messageMemberRejectsSelfAndEmptyBody() {
        assertTrue(collaboration.messageMember(ROOM, "worker", "自言自语", null, false, null, ALLOW_ALL)
                .contains("不能给自己"));
        assertTrue(collaboration.messageMember(ROOM, "manager", "  ", null, false, null, ALLOW_ALL).contains("为空"));
        verify(client, never()).sendText(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void shareToRoomSendsMediaWithGuessedType() {
        collaboration.shareToRoom(DM, null, "mxc://matrix.local/abc", "clip.mp4", null, null, ALLOW_ALL);
        verify(client).sendMedia(eq(DM), eq("m.video"), eq("mxc://matrix.local/abc"), eq("clip.mp4"),
                isNull(), eq(List.of()), isNull(), isNull(), isNull());
        collaboration.shareToRoom(DM, null, "mxc://matrix.local/img", "photo.png", null, null, ALLOW_ALL);
        verify(client).sendMedia(eq(DM), eq("m.image"), eq("mxc://matrix.local/img"), eq("photo.png"),
                isNull(), eq(List.of()), isNull(), isNull(), isNull());
    }

    @Test
    void shareToRoomSendsTextAndRejectsEmptyShare() {
        collaboration.shareToRoom(DM, "成果摘要", null, null, null, null, ALLOW_ALL);
        verify(client).sendText(DM, "成果摘要", List.of(), null, null, null);
        assertTrue(collaboration.shareToRoom(DM, null, null, null, null, null, ALLOW_ALL).contains("没有可分享的内容"));
        assertTrue(collaboration.shareToRoom(null, "文本", null, null, null, null, ALLOW_ALL).contains("缺少目标房间"));
    }

    @Test
    void readRoomReadsTargetRoomHistory() {
        String other = "!other:matrix.local";
        when(client.messages(eq(other), anyInt(), isNull())).thenReturn(json("{\"chunk\":["
                + "{\"event_id\":\"$1\",\"sender\":\"" + MANAGER + "\",\"origin_server_ts\":1000,"
                + "\"type\":\"m.room.message\",\"content\":{\"msgtype\":\"m.text\",\"body\":\"earlier discussion\"}}]}"));
        String text = collaboration.readRoom(other, null, null, ALLOW_ALL);
        assertTrue(text.contains("Manager: earlier discussion"), text);
        verify(client).messages(eq(other), anyInt(), isNull());
    }

    @Test
    void rosterOfMapsLocalpartsToUserIds() {
        var roster = TeamCollaboration.rosterOf(MANAGER, WORKER, null);
        assertEquals(MANAGER, roster.get("manager"));
        assertEquals(WORKER, roster.get("worker"));
        assertEquals(2, roster.size());
    }

    // ---- 归属授权：同一用户可跨自己的房间，绝不能触达他人房间 ----

    @Test
    void listRoomsOnlyShowsOriginAccessibleRooms() {
        when(client.joinedRooms()).thenReturn(json("{\"joined_rooms\":[\"" + ROOM + "\",\"" + FOREIGN + "\"]}"));
        stubRoom(ROOM, "我的团队群", HUMAN, MANAGER, WORKER, SPRING_WORKER);
        stubRoom(FOREIGN, "别人的房间", OTHER_HUMAN, MANAGER, WORKER, SPRING_WORKER);
        String text = collaboration.listRooms(ROOM, ROOM::equals);
        assertTrue(text.contains(ROOM), text);
        assertFalse(text.contains(FOREIGN), text);
        assertFalse(text.contains("intruder"), text);
    }

    @Test
    void readRoomDeniesForeignRoomWithoutFetching() {
        String text = collaboration.readRoom(FOREIGN, null, null, ROOM::equals);
        assertTrue(text.contains("无权访问房间"), text);
        verify(client, never()).messages(eq(FOREIGN), anyInt(), any());
    }

    @Test
    void readRoomAllowsAnotherRoomOfSameUser() {
        String ownDm = "!own-dm:matrix.local";
        when(client.messages(eq(ownDm), anyInt(), isNull())).thenReturn(json("{\"chunk\":["
                + "{\"event_id\":\"$1\",\"sender\":\"" + HUMAN + "\",\"origin_server_ts\":1000,"
                + "\"type\":\"m.room.message\",\"content\":{\"msgtype\":\"m.text\",\"body\":\"我的单聊历史\"}}]}"));
        Predicate<String> ownRooms = roomId -> roomId.equals(ROOM) || roomId.equals(ownDm);
        String text = collaboration.readRoom(ownDm, null, null, ownRooms);
        assertTrue(text.contains("我的单聊历史"), text);
    }

    @Test
    void shareToRoomDeniesForeignRoom() {
        String text = collaboration.shareToRoom(FOREIGN, "成果", null, null, null, null, ROOM::equals);
        assertTrue(text.contains("无权访问房间"), text);
        verify(client, never()).sendText(eq(FOREIGN), anyString(), any(), any(), any(), any());
        verify(client, never()).sendMedia(eq(FOREIGN), anyString(), anyString(), anyString(),
                any(), any(), any(), any(), any());
    }

    @Test
    void messageMemberDeniesForeignTargetRoom() {
        String text = collaboration.messageMember(ROOM, "spring-worker", "发到别处", FOREIGN, false, null, ROOM::equals);
        assertTrue(text.contains("无权访问房间"), text);
        verify(client, never()).sendText(eq(FOREIGN), anyString(), any(), any(), any(), any());
    }

    @Test
    void resolveUserCannotDiscoverMembersOfForeignRoom() {
        when(client.joinedRooms()).thenReturn(json("{\"joined_rooms\":[\"" + ROOM + "\",\"" + FOREIGN + "\"]}"));
        stubRoom(ROOM, "我的团队群", HUMAN, MANAGER, WORKER, SPRING_WORKER);
        stubRoom(FOREIGN, "别人的房间", OTHER_HUMAN, MANAGER, WORKER, SPRING_WORKER);
        // 自己房间的人类成员可解析
        assertEquals(HUMAN, collaboration.resolveUser("huajiang", ROOM::equals));
        // 他人房间的人类成员不可见
        assertThrows(IllegalArgumentException.class, () -> collaboration.resolveUser("intruder", ROOM::equals));
        assertThrows(IllegalArgumentException.class,
                () -> collaboration.resolveUser(OTHER_HUMAN, ROOM::equals));
    }
}