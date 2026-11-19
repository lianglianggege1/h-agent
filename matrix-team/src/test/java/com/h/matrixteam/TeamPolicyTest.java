package com.h.matrixteam;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TeamPolicyTest {

    private static final String MANAGER = "@manager:matrix.local";
    private static final String WORKER = "@worker:matrix.local";
    private static final String HUMAN = "@huajiang:matrix.local";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TeamPolicy manager = new TeamPolicy(TeamPolicy.Role.MANAGER, MANAGER, WORKER);
    private final TeamPolicy worker = new TeamPolicy(TeamPolicy.Role.WORKER, WORKER, MANAGER);

    private static TeamMessage message(String sender, String body, List<String> mentions, String kind, String taskId) {
        return new TeamMessage("$evt", "!room:matrix.local", sender, body, mentions, false, kind, taskId);
    }

    private static TeamMessage human(String body, List<String> mentions) {
        return message(HUMAN, body, mentions, null, null);
    }

    @Test
    void managerAnswersUnmentionedHumanMessages() {
        assertTrue(manager.shouldRespond(human("帮我分析这个需求", List.of())));
    }

    @Test
    void managerSkipsHumanMessageMentioningOnlyWorker() {
        assertFalse(manager.shouldRespond(human("直接问 worker", List.of(WORKER))));
        assertTrue(worker.shouldRespond(human("直接问 worker", List.of(WORKER))));
    }

    @Test
    void bothAgentsRespondWhenBothMentioned() {
        TeamMessage both = human("你们各自看一下", List.of(MANAGER, WORKER));
        assertTrue(manager.shouldRespond(both));
        assertTrue(worker.shouldRespond(both));
    }

    @Test
    void workerOnlyActsOnDelegationOrMention() {
        assertFalse(worker.shouldRespond(human("大家随便聊聊", List.of())));
        assertFalse(worker.shouldRespond(message(MANAGER, "旁听信息", List.of(), null, null)));
        assertTrue(worker.shouldRespond(message(MANAGER, "请检查代码", List.of(WORKER),
                TeamProtocol.KIND_DELEGATE, "task-1")));
        assertTrue(worker.shouldRespond(message(MANAGER, "补充一点", List.of(WORKER), null, null)));
    }

    @Test
    void managerHandlesWorkerReportsAndQuestions() {
        assertTrue(manager.shouldRespond(message(WORKER, "完成", List.of(), TeamProtocol.KIND_REPORT, "task-1")));
        assertTrue(manager.shouldRespond(message(WORKER, "缺少信息", List.of(), TeamProtocol.KIND_QUESTION, "task-1")));
        assertFalse(manager.shouldRespond(message(WORKER, "收到", List.of(), null, null)));
        assertTrue(manager.shouldRespond(message(WORKER, "请确认", List.of(MANAGER), null, null)));
    }

    @Test
    void selfMessagesNeverTrigger() {
        assertFalse(manager.shouldRespond(message(MANAGER, "自己的话", List.of(MANAGER), null, null)));
        assertFalse(worker.shouldRespond(message(WORKER, "自己的话", List.of(WORKER), null, null)));
    }

    @Test
    void parsesStructuredMentionsAndTeamMetadata() throws Exception {
        ObjectNode event = JSON.createObjectNode();
        event.put("type", "m.room.message");
        event.put("event_id", "$abc");
        event.put("sender", MANAGER);
        ObjectNode content = event.putObject("content");
        content.put("msgtype", "m.text");
        content.put("body", "请执行任务");
        content.putObject("m.mentions").putArray("user_ids").add(WORKER);
        content.putObject(TeamProtocol.CONTENT_KEY).put("kind", "delegate").put("taskId", "task-9");

        TeamMessage parsed = TeamProtocol.parse("!room:matrix.local", event);
        assertEquals("$abc", parsed.eventId());
        assertEquals(WORKER, parsed.mentions().getFirst());
        assertEquals("delegate", parsed.kind());
        assertEquals("task-9", parsed.taskId());
        assertTrue(parsed.mentions(WORKER));
    }

    @Test
    void ignoresNonTextEvents() {
        ObjectNode event = JSON.createObjectNode();
        event.put("type", "m.room.member");
        assertNull(TeamProtocol.parse("!room:matrix.local", event));

        ObjectNode image = JSON.createObjectNode();
        image.put("type", "m.room.message");
        image.putObject("content").put("msgtype", "m.image").put("body", "pic.png");
        assertNull(TeamProtocol.parse("!room:matrix.local", image));
    }
}