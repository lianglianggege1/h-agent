package com.h.matrixteam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    private static final String ROOM = "!room:matrix.local";
    private static final String DM = "!dm:matrix.local";

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
        String text = collaboration.listRooms(ROOM);
        assertTrue(text.contains("群聊（当前） 团队群 [" + ROOM + "]"), text);
        assertTrue(text.contains("单聊 " + DM + " [" + DM + "]"), text);
        assertTrue(text.contains("自己（Worker）"), text);
        assertTrue(text.contains("Manager"), text);
        assertTrue(text.contains("Human（huajiang）"), text);
    }

    @Test
    void resolveUserByNameUserIdAndRoomScan() {
        assertEquals(MANAGER, collaboration.resolveUser("manager"));
        assertEquals(SPRING_WORKER, collaboration.resolveUser("@spring-worker:matrix.local"));
        assertEquals(SPRING_WORKER, collaboration.resolveUser("spring-worker"));
        when(client.joinedRooms()).thenReturn(json("{\"joined_rooms\":[\"" + ROOM + "\"]}"));
        stubRoom(ROOM, "团队群", HUMAN, MANAGER, WORKER, SPRING_WORKER);
        assertEquals(HUMAN, collaboration.resolveUser("huajiang"));
        assertThrows(IllegalArgumentException.class, () -> collaboration.resolveUser("nobody"));
    }

    @Test
    void messageMemberInRoomMentionsTarget() {
        String result = collaboration.messageMember(ROOM, "spring-worker", "帮我查个资料", null, false);
        verify(client).sendText(ROOM, "帮我查个资料", List.of(SPRING_WORKER), null, null, null);
        assertTrue(result.contains("已向 spring-worker 发送消息"), result);
    }

    @Test
    void messageMemberDirectReusesExistingDm() {
        when(client.joinedRooms()).thenReturn(json("{\"joined_rooms\":[\"" + ROOM + "\",\"" + DM + "\"]}"));
        stubRoom(ROOM, "团队群", HUMAN, MANAGER, WORKER, SPRING_WORKER);
        stubRoom(DM, null, WORKER, SPRING_WORKER);
        collaboration.messageMember(ROOM, "spring-worker", "私下说", null, true);
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
        collaboration.messageMember(ROOM, "manager", "同步一下进度", null, true);
        verify(client).createDm(MANAGER);
        verify(client).sendText(newDm, "同步一下进度", List.of(MANAGER), null, null, null);
    }

    @Test
    void messageMemberRejectsSelfAndEmptyBody() {
        assertTrue(collaboration.messageMember(ROOM, "worker", "自言自语", null, false).contains("不能给自己"));
        assertTrue(collaboration.messageMember(ROOM, "manager", "  ", null, false).contains("为空"));
        verify(client, never()).sendText(anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void shareToRoomSendsMediaWithGuessedType() {
        collaboration.shareToRoom(DM, null, "mxc://matrix.local/abc", "clip.mp4", null);
        verify(client).sendMedia(eq(DM), eq("m.video"), eq("mxc://matrix.local/abc"), eq("clip.mp4"),
                isNull(), eq(List.of()), isNull(), isNull(), isNull());
        collaboration.shareToRoom(DM, null, "mxc://matrix.local/img", "photo.png", null);
        verify(client).sendMedia(eq(DM), eq("m.image"), eq("mxc://matrix.local/img"), eq("photo.png"),
                isNull(), eq(List.of()), isNull(), isNull(), isNull());
    }

    @Test
    void shareToRoomSendsTextAndRejectsEmptyShare() {
        collaboration.shareToRoom(DM, "成果摘要", null, null, null);
        verify(client).sendText(DM, "成果摘要", List.of(), null, null, null);
        assertTrue(collaboration.shareToRoom(DM, null, null, null, null).contains("没有可分享的内容"));
        assertTrue(collaboration.shareToRoom(null, "文本", null, null, null).contains("缺少目标房间"));
    }

    @Test
    void readRoomReadsTargetRoomHistory() {
        String other = "!other:matrix.local";
        when(client.messages(eq(other), anyInt(), isNull())).thenReturn(json("{\"chunk\":["
                + "{\"event_id\":\"$1\",\"sender\":\"" + MANAGER + "\",\"origin_server_ts\":1000,"
                + "\"type\":\"m.room.message\",\"content\":{\"msgtype\":\"m.text\",\"body\":\"earlier discussion\"}}]}"));
        String text = collaboration.readRoom(other, null, null);
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
}