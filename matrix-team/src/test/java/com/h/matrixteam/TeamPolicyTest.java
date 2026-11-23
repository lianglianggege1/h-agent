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
    private static final String SPRING_WORKER = "@spring-worker:matrix.local";
    private static final String HUMAN = "@huajiang:matrix.local";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final TeamPolicy manager = new TeamPolicy(TeamPolicy.Role.MANAGER, MANAGER, MANAGER, "!room:matrix.local", WORKER, SPRING_WORKER);
    private final TeamPolicy worker = new TeamPolicy(TeamPolicy.Role.WORKER, WORKER, MANAGER, "!room:matrix.local", WORKER, SPRING_WORKER);
    private final TeamPolicy springWorker = new TeamPolicy(TeamPolicy.Role.WORKER, SPRING_WORKER, MANAGER, "!room:matrix.local", WORKER, SPRING_WORKER);

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
    void delegationOnlyTriggersTheMentionedWorker() {
        TeamMessage toWorker = message(MANAGER, "请检查代码", List.of(WORKER), TeamProtocol.KIND_DELEGATE, "task-1");
        assertTrue(worker.shouldRespond(toWorker));
        assertFalse(springWorker.shouldRespond(toWorker));
        TeamMessage toSpring = message(MANAGER, "请整理资料", List.of(SPRING_WORKER), TeamProtocol.KIND_DELEGATE, "task-2");
        assertTrue(springWorker.shouldRespond(toSpring));
        assertFalse(worker.shouldRespond(toSpring));
    }

    @Test
    void workersIgnoreEachOthersReportsButManagerHandlesThem() {
        TeamMessage report = message(SPRING_WORKER, "完成", List.of(MANAGER), TeamProtocol.KIND_REPORT, "task-2");
        assertFalse(worker.shouldRespond(report));
        assertTrue(manager.shouldRespond(report));
        assertTrue(manager.isAgent(SPRING_WORKER));
        assertTrue(worker.isAgent(SPRING_WORKER));
    }

    @Test
    void speakerLabelsDistinguishManagerAndEachWorker() {
        assertEquals("Manager", worker.speakerLabel(MANAGER));
        assertEquals("Worker（spring-worker）", worker.speakerLabel(SPRING_WORKER));
        assertEquals("Worker（worker）", manager.speakerLabel(WORKER));
        assertEquals("自己（Worker）", springWorker.speakerLabel(SPRING_WORKER));
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
    void predicateClassifierTreatsEveryPerUserTeamRoomAsGroup() {
        java.util.Set<String> teamRooms = java.util.Set.of("!teamA:matrix.local", "!teamB:matrix.local");
        TeamPolicy managerMulti = new TeamPolicy(TeamPolicy.Role.MANAGER, MANAGER, MANAGER,
                teamRooms::contains, WORKER, SPRING_WORKER);
        TeamPolicy workerMulti = new TeamPolicy(TeamPolicy.Role.WORKER, WORKER, MANAGER,
                teamRooms::contains, WORKER, SPRING_WORKER);
        // 两个不同用户的专属团队房间都按群聊规则：未点名人类消息只由 Manager 接待，Worker 不抢答。
        for (String room : teamRooms) {
            TeamMessage unmentioned = new TeamMessage("$e", room, HUMAN, "帮我看看", List.of(), false, null, null);
            assertTrue(managerMulti.shouldRespond(unmentioned));
            assertFalse(workerMulti.shouldRespond(unmentioned));
            TeamMessage toWorker = new TeamMessage("$d", room, MANAGER, "请执行", List.of(WORKER),
                    false, TeamProtocol.KIND_DELEGATE, "task-x");
            assertTrue(workerMulti.shouldRespond(toWorker));
        }
        // 非团队房间（单聊）仍按单聊规则：人类消息无需点名即触发 Worker。
        TeamMessage dm = new TeamMessage("$dm", "!dm:matrix.local", HUMAN, "在吗", List.of(), false, null, null);
        assertTrue(workerMulti.shouldRespond(dm));
    }

    @Test
    void dmRoomsTriggerOnAnyHumanMessageWithoutMentions() {
        TeamMessage dmHuman = new TeamMessage("$dm1", "!dm:matrix.local", HUMAN, "在吗", List.of(), false, null, null);
        assertTrue(manager.shouldRespond(dmHuman));
        assertTrue(worker.shouldRespond(dmHuman));
        assertTrue(springWorker.shouldRespond(dmHuman));
        TeamMessage dmAgent = new TeamMessage("$dm2", "!dm:matrix.local", WORKER, "旁听", List.of(), false, null, null);
        assertFalse(manager.shouldRespond(dmAgent));
        assertFalse(springWorker.shouldRespond(dmAgent));
    }

    @Test
    void dmPeerMessagesTriggerOnlyTheMentionedMember() {
        TeamMessage toSpring = new TeamMessage("$dm3", "!dm:matrix.local", WORKER, "帮我查个资料",
                List.of(SPRING_WORKER), false, null, null);
        assertTrue(springWorker.shouldRespond(toSpring));
        assertFalse(manager.shouldRespond(toSpring));
        assertFalse(worker.shouldRespond(toSpring));
        TeamMessage toManager = new TeamMessage("$dm4", "!dm:matrix.local", SPRING_WORKER, "进度同步",
                List.of(MANAGER), false, null, null);
        assertTrue(manager.shouldRespond(toManager));
        assertFalse(worker.shouldRespond(toManager));
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

        ObjectNode notice = JSON.createObjectNode();
        notice.put("type", "m.room.message");
        notice.putObject("content").put("msgtype", "m.notice").put("body", "bot notice");
        assertNull(TeamProtocol.parse("!room:matrix.local", notice));
    }

    @Test
    void parsesMediaMessagesAndBlankBodyFallsBackToUrlName() {
        ObjectNode event = JSON.createObjectNode();
        event.put("type", "m.room.message");
        event.put("event_id", "$img");
        event.put("sender", HUMAN);
        ObjectNode content = event.putObject("content");
        content.put("msgtype", "m.image");
        content.put("body", "");
        content.put("url", "mxc://matrix.local/photo123");
        content.putObject("info").put("mimetype", "image/png").put("size", 1024);

        TeamMessage parsed = TeamProtocol.parse("!room:matrix.local", event);
        assertEquals("m.image", parsed.msgtype());
        assertEquals("photo123", parsed.body());
        assertTrue(parsed.isMedia());
        assertEquals("image/png", parsed.mimetype());
        assertEquals(1024, parsed.size());
        assertTrue(manager.shouldRespond(parsed));
        assertFalse(worker.shouldRespond(parsed));
    }
}