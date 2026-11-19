package com.h.matrixteam;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamHistoryReaderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ROOM = "!room:matrix.local";
    private static final TeamPolicy MANAGER_POLICY = new TeamPolicy(
            TeamPolicy.Role.MANAGER, "@manager:matrix.local", "@manager:matrix.local", ROOM, "@worker:matrix.local");
    private static final TeamPolicy WORKER_POLICY = new TeamPolicy(
            TeamPolicy.Role.WORKER, "@worker:matrix.local", "@manager:matrix.local", ROOM, "@worker:matrix.local");

    private static String event(String id, String sender, long ts, String body, String teamJson) {
        String content = "{\"msgtype\":\"m.text\",\"body\":" + JSON.valueToTree(body)
                + (teamJson == null ? "" : ",\"com.h.team\":" + teamJson) + "}";
        return "{\"event_id\":\"" + id + "\",\"room_id\":\"" + ROOM + "\",\"sender\":\"" + sender
                + "\",\"origin_server_ts\":" + ts + ",\"type\":\"m.room.message\",\"content\":" + content + "}";
    }

    @Test
    void formatsChronologicallyWithSpeakerIdentities() throws Exception {
        // /messages 返回最新在前
        JsonNode response = JSON.readTree("{\"chunk\":["
                + event("$3", "@manager:matrix.local", 3000, "我来汇总", null) + ","
                + event("$2", "@worker:matrix.local", 2000, "检查完成", "{\"kind\":\"report\",\"taskId\":\"task-1\"}") + ","
                + event("$1", "@huajiang:matrix.local", 1000, "帮我检查代码", null)
                + "],\"end\":\"t99\"}");
        String text = TeamHistoryReader.format(response, ROOM, MANAGER_POLICY);
        String[] lines = text.split("\n");
        assertEquals(3, lines.length);
        assertTrue(lines[0].contains("Human（huajiang）: 帮我检查代码"), lines[0]);
        assertTrue(lines[1].contains("Worker（worker）（回报 task-1）: 检查完成"), lines[1]);
        assertTrue(lines[2].contains("自己（Manager）: 我来汇总"), lines[2]);
    }

    @Test
    void workerPerspectiveLabelsItselfAndManager() throws Exception {
        JsonNode response = JSON.readTree("{\"chunk\":["
                + event("$2", "@worker:matrix.local", 2000, "收到", null) + ","
                + event("$1", "@manager:matrix.local", 1000, "[委派 task-1] 检查代码", "{\"kind\":\"delegate\",\"taskId\":\"task-1\"}")
                + "]}");
        String text = TeamHistoryReader.format(response, ROOM, WORKER_POLICY);
        assertTrue(text.contains("Manager（委派 task-1）"), text);
        assertTrue(text.contains("自己（Worker）: 收到"), text);
        assertFalse(text.contains("Human（manager）"), text);
    }

    @Test
    void truncatesLongBodiesAndSkipsNonTextEvents() throws Exception {
        String longBody = "x".repeat(900);
        JsonNode response = JSON.readTree("{\"chunk\":["
                + event("$2", "@huajiang:matrix.local", 2000, longBody, null) + ","
                + "{\"event_id\":\"$1\",\"type\":\"m.room.member\",\"sender\":\"@huajiang:matrix.local\",\"origin_server_ts\":1000,\"content\":{}}"
                + "]}");
        String text = TeamHistoryReader.format(response, ROOM, MANAGER_POLICY);
        assertEquals(1, text.split("\n").length);
        assertTrue(text.contains("…（已截断）"), text);
        assertFalse(text.contains("x".repeat(801)), text);
    }

    @Test
    void emptyChunkReportsNoHistory() throws Exception {
        assertEquals("（该范围内没有历史消息）",
                TeamHistoryReader.format(JSON.readTree("{\"chunk\":[]}"), ROOM, MANAGER_POLICY));
    }
}