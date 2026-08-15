package com.h.backend.chat;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.HarnessCollaborationService;
import com.h.backend.chat.application.HarnessSubagentExposure;
import com.h.backend.chat.application.HarnessSubagentFailureReason;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.AgentSessionMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatSessionMessageMapper;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceUseDto;
import com.h.backend.user.infrastructure.persistence.entity.UserEntity;
import com.h.backend.user.infrastructure.persistence.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import com.h.backend.shared.infrastructure.security.JwtTokenProvider;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class HarnessCollaborationSessionOpenIT {

    @Autowired
    private ChatSessionService chatSessionService;

    @Autowired
    private HarnessCollaborationService harnessCollaborationService;

    @Autowired
    private AgentSessionMapper agentSessionMapper;

    @Autowired
    private ChatSessionMessageMapper chatSessionMessageMapper;

    @Autowired
    private ChatMessageResourceMapper chatMessageResourceMapper;

    @Autowired
    private UserMapper userMapper;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void harnessSessionOpenIncludesEmptySubagentList() {
        UserEntity user = createUser();

        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);

        assertNotNull(opened.subagents());
        assertTrue(opened.subagents().isEmpty());
        var root = agentSessionMapper.selectBySessionId(opened.session().sessionId());
        assertNotNull(root);
        assertEquals(null, root.getParentSessionId());
    }

    @Test
    void exposedSubagentCanBeRecoveredFromSubagentList() {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);

        harnessCollaborationService.exposeSubagent(
                user.getId(),
                opened.session().sessionId(),
                new HarnessSubagentExposure(
                        "research",
                        "research-agent",
                        opened.session().sessionId(),
                        "child-runtime-research",
                        "资料收集",
                        "收集官方资料并给出来源"
                )
        );

        var subagents = harnessCollaborationService.listSubagents(user.getId(), opened.session().sessionId());

        assertEquals(1, subagents.size());
        var research = subagents.getFirst();
        assertEquals("child-runtime-research", research.sessionId());
        assertEquals(opened.session().sessionId(), research.parentSessionId());
        assertEquals("资料收集", research.displayName());
        assertEquals("收集官方资料并给出来源", research.assignment());
        assertEquals("AVAILABLE", research.status().name());
        assertEquals(0, research.displayOrder());

        var thread = chatSessionService.getSessionMessages(
                user.getId(), "child-runtime-research", 20, null
        );
        assertEquals(1, thread.messages().size());
        assertEquals("system", thread.messages().getFirst().role());
        assertEquals("SYSTEM", thread.messages().getFirst().messageType());
        assertEquals("收集官方资料并给出来源", thread.messages().getFirst().content());
    }

    @Test
    void childLifecycleAssignmentWinsWhenItStartsBeforeExposureProjection() {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);

        harnessCollaborationService.projectSubagentAssignment(
                user.getId(), "child-runtime-race", "运行中就必须展示的完整父委托"
        );
        var exposed = harnessCollaborationService.exposeSubagent(
                user.getId(),
                opened.session().sessionId(),
                new HarnessSubagentExposure(
                        "race", "research-agent", opened.session().sessionId(),
                        "child-runtime-race", "资料收集", "资料收集"
                )
        );

        assertEquals("运行中就必须展示的完整父委托", exposed.assignment());
        var thread = chatSessionService.getSessionMessages(
                user.getId(), "child-runtime-race", 20, null
        );
        assertEquals("运行中就必须展示的完整父委托", thread.messages().getFirst().content());
    }

    @Test
    void subagentListPreservesArbitraryDepthParentLinks() {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);
        harnessCollaborationService.exposeSubagent(
                user.getId(), opened.session().sessionId(),
                new HarnessSubagentExposure(
                        "child", "child-agent", opened.session().sessionId(), "agent-session-child",
                        "一级协作者", "拆分任务"
                )
        );
        harnessCollaborationService.exposeSubagent(
                user.getId(), opened.session().sessionId(),
                new HarnessSubagentExposure(
                        "grandchild", "grandchild-agent", "agent-session-child", "agent-session-grandchild",
                        "二级协作者", "继续拆分任务"
                )
        );

        var subagents = harnessCollaborationService.listSubagents(user.getId(), opened.session().sessionId());

        assertEquals(2, subagents.size());
        assertEquals(opened.session().sessionId(), subagents.get(0).parentSessionId());
        assertEquals("agent-session-child", subagents.get(1).parentSessionId());
    }

    @Test
    void reopeningHarnessSessionIncludesPersistedSubagents() {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);
        harnessCollaborationService.exposeSubagent(
                user.getId(),
                opened.session().sessionId(),
                new HarnessSubagentExposure(
                        "compare",
                        "compare-agent",
                        opened.session().sessionId(),
                        "child-runtime-compare",
                        "功能对比",
                        "对比产品 A 与产品 B"
                )
        );

        var reopened = chatSessionService.activateHistorySession(
                user.getId(),
                opened.session().sessionId(),
                opened.session().sessionId()
        );

        assertEquals("child-runtime-compare", reopened.subagents().getFirst().sessionId());
    }

    @Test
    void userCanRefreshSubagentsWithoutReloadingParentMessages() throws Exception {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);
        harnessCollaborationService.exposeSubagent(
                user.getId(),
                opened.session().sessionId(),
                new HarnessSubagentExposure(
                        "summary",
                        "summary-agent",
                        opened.session().sessionId(),
                        "child-runtime-summary",
                        "结论汇总",
                        "汇总其他协作 Agent 的结果"
                )
        );
        String token = jwtTokenProvider.generateToken(user.getId(), user.getEmail(), "USER");

        mockMvc.perform(get("/api/chat/sessions/{sessionId}/subagents", opened.session().sessionId())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].subagentId").doesNotExist());
    }

    @Test
    void exposedSubagentBecomesRunningWhenItsTurnStarts() {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);
        harnessCollaborationService.exposeSubagent(
                user.getId(),
                opened.session().sessionId(),
                new HarnessSubagentExposure(
                        "research-running",
                        "research-agent",
                        opened.session().sessionId(),
                        "child-runtime-running",
                        "资料收集",
                        "收集官方资料"
                )
        );

        harnessCollaborationService.markRunning(
                user.getId(),
                opened.session().sessionId(),
                "child-runtime-running",
                "execution-running"
        );

        var subagents = harnessCollaborationService.listSubagents(user.getId(), opened.session().sessionId());
        assertEquals("RUNNING", subagents.getFirst().status().name());
    }

    @Test
    void completedSubagentResultIsCommittedToItsOwnThread() {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);
        harnessCollaborationService.exposeSubagent(
                user.getId(),
                opened.session().sessionId(),
                new HarnessSubagentExposure(
                        "compare-complete",
                        "compare-agent",
                        opened.session().sessionId(),
                        "child-runtime-complete",
                        "功能对比",
                        "对比两个产品的核心能力"
                )
        );
        harnessCollaborationService.markRunning(
                user.getId(),
                opened.session().sessionId(),
                "child-runtime-complete",
                "execution-complete"
        );

        harnessCollaborationService.completeSubagent(
                user.getId(),
                opened.session().sessionId(),
                "child-runtime-complete",
                "execution-complete",
                "产品 A 能力完整，产品 B 更轻量。"
        );

        var subagents = harnessCollaborationService.listSubagents(user.getId(), opened.session().sessionId());
        var thread = chatSessionService.getSessionMessages(
                user.getId(), "child-runtime-complete", 20, null
        );
        assertEquals("COMPLETED", subagents.getFirst().status().name());
        assertEquals(2, thread.messages().size());
        assertEquals("system", thread.messages().getFirst().role());
        assertEquals("对比两个产品的核心能力", thread.messages().getFirst().content());
        assertEquals("assistant", thread.messages().getLast().role());
        assertEquals("产品 A 能力完整，产品 B 更轻量。", thread.messages().getLast().content());
    }

    @Test
    void childCompletionBoundaryPersistsReplyAsTheOnlyWritePath() {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);
        harnessCollaborationService.exposeSubagent(
                user.getId(), opened.session().sessionId(),
                new HarnessSubagentExposure(
                        "summer-live", "general-purpose", opened.session().sessionId(),
                        "child-runtime-summer-live", "散文·夏", "散文·夏"
                )
        );

        harnessCollaborationService.projectSubagentResult(
                user.getId(), "child-runtime-summer-live",
                "写一篇描绘夏日傍晚的散文，约 500 字。",
                null, "蝉鸣落在黄昏里。"
        );
        var thread = chatSessionService.getSessionMessages(
                user.getId(), "child-runtime-summer-live", 20, null
        );

        assertEquals(2, thread.messages().size());
        assertEquals("写一篇描绘夏日傍晚的散文，约 500 字。", thread.messages().getFirst().content());
        assertEquals("蝉鸣落在黄昏里。", thread.messages().getLast().content());
    }

    @Test
    void childCompletionBoundaryPersistsReasoningBeforeReply() {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);
        harnessCollaborationService.exposeSubagent(
                user.getId(), opened.session().sessionId(),
                new HarnessSubagentExposure(
                        "summer-reasoning", "general-purpose", opened.session().sessionId(),
                        "child-runtime-summer-reasoning", "散文·夏", "写一篇夏日散文"
                )
        );

        harnessCollaborationService.projectSubagentResult(
                user.getId(), "child-runtime-summer-reasoning", "写一篇夏日散文",
                "先确定黄昏、蝉鸣和晚风三个意象。", "蝉鸣落在黄昏里。"
        );
        var thread = chatSessionService.getSessionMessages(
                user.getId(), "child-runtime-summer-reasoning", 20, null
        );

        assertEquals(3, thread.messages().size());
        assertEquals("SYSTEM", thread.messages().get(0).messageType());
        assertEquals("REASONING", thread.messages().get(1).messageType());
        assertEquals("先确定黄昏、蝉鸣和晚风三个意象。", thread.messages().get(1).content());
        assertEquals("AI", thread.messages().get(2).messageType());
        assertEquals("蝉鸣落在黄昏里。", thread.messages().get(2).content());
    }

    @Test
    void delayedSseCompletionReusesReplyPersistedAtChildCompletionBoundary() {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);
        harnessCollaborationService.exposeSubagent(
                user.getId(), opened.session().sessionId(),
                new HarnessSubagentExposure(
                        "summer-race", "general-purpose", opened.session().sessionId(),
                        "child-runtime-summer-race", "散文·夏", "写一篇夏日散文"
                )
        );
        harnessCollaborationService.markRunning(
                user.getId(), opened.session().sessionId(),
                "child-runtime-summer-race", "reply-summer-race"
        );
        harnessCollaborationService.projectSubagentResult(
                user.getId(), "child-runtime-summer-race", "写一篇夏日散文",
                "先确定黄昏这个时间锚点。", "蝉鸣落在黄昏里。"
        );

        var delayed = harnessCollaborationService.completeSubagent(
                user.getId(), opened.session().sessionId(),
                "child-runtime-summer-race", "reply-summer-race", "蝉鸣落在黄昏里。"
        );
        var thread = chatSessionService.getSessionMessages(
                user.getId(), "child-runtime-summer-race", 20, null
        );

        assertNotNull(delayed.assistantMessageId());
        assertEquals(3, thread.messages().size());
        assertEquals("REASONING", thread.messages().get(1).messageType());
        assertEquals("AI", thread.messages().get(2).messageType());
    }

    @Test
    void childCompletionBoundaryPersistsFollowUpWithoutOverwritingParentAssignment() {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);
        harnessCollaborationService.exposeSubagent(
                user.getId(), opened.session().sessionId(),
                new HarnessSubagentExposure(
                        "summer-follow-up", "general-purpose", opened.session().sessionId(),
                        "child-runtime-summer-follow-up", "散文·夏", "写一篇夏日散文"
                )
        );
        harnessCollaborationService.projectSubagentResult(
                user.getId(), "child-runtime-summer-follow-up", "写一篇夏日散文",
                null, "蝉鸣落在黄昏里。"
        );
        harnessCollaborationService.beginSubagentTurn(
                user.getId(), opened.session().sessionId(), "child-runtime-summer-follow-up",
                "再增加一些晚风的描写", java.util.List.of()
        );

        harnessCollaborationService.projectSubagentResult(
                user.getId(), "child-runtime-summer-follow-up",
                "再增加一些晚风的描写", null, "晚风从荷叶间穿过。"
        );
        var thread = chatSessionService.getSessionMessages(
                user.getId(), "child-runtime-summer-follow-up", 20, null
        );
        var subagent = harnessCollaborationService.listSubagents(
                user.getId(), opened.session().sessionId()
        ).getFirst();

        assertEquals(4, thread.messages().size());
        assertEquals("system", thread.messages().get(0).role());
        assertEquals("assistant", thread.messages().get(1).role());
        assertEquals("user", thread.messages().get(2).role());
        assertEquals("assistant", thread.messages().get(3).role());
        assertEquals("写一篇夏日散文", subagent.assignment());
    }

    @Test
    void userCanPageACompletedSubagentThreadOverHttp() throws Exception {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);
        harnessCollaborationService.exposeSubagent(
                user.getId(),
                opened.session().sessionId(),
                new HarnessSubagentExposure(
                        "http-thread",
                        "research-agent",
                        opened.session().sessionId(),
                        "child-runtime-http-thread",
                        "资料收集",
                        "收集并整理公开资料"
                )
        );
        harnessCollaborationService.markRunning(
                user.getId(), opened.session().sessionId(), "child-runtime-http-thread", "execution-http"
        );
        harnessCollaborationService.completeSubagent(
                user.getId(), opened.session().sessionId(), "child-runtime-http-thread", "execution-http",
                "已经整理出三条关键资料。"
        );
        String token = jwtTokenProvider.generateToken(user.getId(), user.getEmail(), "USER");

        mockMvc.perform(get(
                                "/api/chat/sessions/{sessionId}/messages",
                                "child-runtime-http-thread"
                        )
                        .header("Authorization", "Bearer " + token)
                        .queryParam("limit", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages[0].role").value("system"))
                .andExpect(jsonPath("$.data.messages[0].messageType").value("SYSTEM"))
                .andExpect(jsonPath("$.data.messages[0].content").value("收集并整理公开资料"))
                .andExpect(jsonPath("$.data.messages[1].role").value("assistant"))
                .andExpect(jsonPath("$.data.messages[1].content").value("已经整理出三条关键资料。"))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void parentAndChildSessionsAllocateIndependentMessageSequences() {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);
        chatSessionService.appendUserMessage(
                user.getId(), opened.session().sessionId(), "父 Agent 的第一条消息", null
        );
        harnessCollaborationService.exposeSubagent(
                user.getId(), opened.session().sessionId(),
                new HarnessSubagentExposure(
                        "gateway-page", "research-agent", opened.session().sessionId(), "child-runtime-page",
                        "资料收集", "验证子会话分页"
                )
        );
        harnessCollaborationService.markRunning(
                user.getId(), opened.session().sessionId(), "child-runtime-page", "execution-page"
        );
        harnessCollaborationService.completeSubagent(
                user.getId(), opened.session().sessionId(), "child-runtime-page", "execution-page",
                "子 Agent 唯一一条消息"
        );

        var page = chatSessionService.getSessionMessages(user.getId(), "child-runtime-page", 20, null);
        var parentMessages = chatSessionMessageMapper.selectPageByAgentSessionId(
                opened.session().sessionId(), 20, null
        );
        var childMessages = chatSessionMessageMapper.selectPageByAgentSessionId(
                "child-runtime-page", 20, null
        );

        assertEquals(2, page.messages().size());
        assertEquals(false, page.hasMore());
        assertEquals(null, page.nextBeforeSeq());
        assertEquals(1, parentMessages.getFirst().getSequenceNo());
        assertEquals("AI", childMessages.getFirst().getMessageType());
        assertEquals(2, childMessages.getFirst().getSequenceNo());
        assertEquals("SYSTEM", childMessages.getLast().getMessageType());
        assertEquals(1, childMessages.getLast().getSequenceNo());
    }

    @Test
    void completedSubagentAcceptsAUserFollowUpAndStartsAnotherTurn() {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);
        harnessCollaborationService.exposeSubagent(
                user.getId(),
                opened.session().sessionId(),
                new HarnessSubagentExposure(
                        "follow-up",
                        "research-agent",
                        opened.session().sessionId(),
                        "child-runtime-follow-up",
                        "资料收集",
                        "收集公开资料"
                )
        );
        harnessCollaborationService.markRunning(
                user.getId(), opened.session().sessionId(), "child-runtime-follow-up", "execution-follow-up"
        );
        harnessCollaborationService.completeSubagent(
                user.getId(), opened.session().sessionId(), "child-runtime-follow-up", "execution-follow-up",
                "第一轮资料已经整理完成。"
        );

        var turn = harnessCollaborationService.beginSubagentTurn(
                user.getId(),
                opened.session().sessionId(),
                "child-runtime-follow-up",
                "补充近三个月的数据，并标注来源。",
                null
        );

        var subagents = harnessCollaborationService.listSubagents(user.getId(), opened.session().sessionId());
        var thread = chatSessionService.getSessionMessages(
                user.getId(), "child-runtime-follow-up", 20, null
        );
        assertNotNull(turn.userMessageId());
        assertEquals("RUNNING", turn.subagent().status().name());
        assertEquals("RUNNING", subagents.getFirst().status().name());
        assertEquals(3, thread.messages().size());
        assertEquals("system", thread.messages().getFirst().role());
        assertEquals("user", thread.messages().getLast().role());
        assertEquals("补充近三个月的数据，并标注来源。", thread.messages().getLast().content());
    }

    @Test
    void staleTerminalEventsCannotOverwriteANewerSubagentTurn() {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);
        String rootSessionId = opened.session().sessionId();
        String childSessionId = "child-runtime-stale-" + UUID.randomUUID();
        harnessCollaborationService.exposeSubagent(
                user.getId(), rootSessionId,
                new HarnessSubagentExposure(
                        "stale-events", "research-agent", rootSessionId, childSessionId,
                        "资料收集", "验证迟到终态"
                )
        );
        harnessCollaborationService.markRunning(
                user.getId(), rootSessionId, childSessionId, "execution-old"
        );
        harnessCollaborationService.completeSubagent(
                user.getId(), rootSessionId, childSessionId, "execution-old", "第一轮完成。"
        );
        var newerTurn = harnessCollaborationService.beginSubagentTurn(
                user.getId(), rootSessionId, childSessionId, "开始第二轮。", null
        );

        var afterLateFailure = harnessCollaborationService.failSubagent(
                user.getId(), rootSessionId, childSessionId, "execution-old",
                HarnessSubagentFailureReason.EXECUTION_ERROR, "late failure"
        );
        var afterLateCompletion = harnessCollaborationService.completeSubagent(
                user.getId(), rootSessionId, childSessionId, "execution-old", "迟到的重复结果。"
        );
        var thread = chatSessionService.getSessionMessages(user.getId(), childSessionId, 20, null);

        assertNotNull(newerTurn.executionId());
        assertEquals("RUNNING", afterLateFailure.status().name());
        assertEquals("RUNNING", afterLateCompletion.subagent().status().name());
        assertEquals(null, afterLateCompletion.assistantMessageId());
        assertEquals(3, thread.messages().size());
        assertEquals("验证迟到终态", thread.messages().getFirst().content());
        assertEquals("第一轮完成。", thread.messages().get(1).content());
        assertEquals("开始第二轮。", thread.messages().getLast().content());
    }

    @Test
    void subagentFollowUpBindsResourcesInTheSameTurnTransaction() {
        UserEntity user = createUser();
        var opened = chatSessionService.createSession(user.getId(), null, ChatAgentIds.HARNESS, null);
        String rootSessionId = opened.session().sessionId();
        String childSessionId = "child-runtime-resource-" + UUID.randomUUID();
        harnessCollaborationService.exposeSubagent(
                user.getId(), rootSessionId,
                new HarnessSubagentExposure(
                        "resource", "research-agent", rootSessionId, childSessionId,
                        "资料收集", "分析用户附件"
                )
        );
        harnessCollaborationService.markRunning(
                user.getId(), rootSessionId, childSessionId, "execution-resource"
        );
        harnessCollaborationService.completeSubagent(
                user.getId(), rootSessionId, childSessionId, "execution-resource", "第一轮已完成。"
        );

        String resourceId = "resource-" + UUID.randomUUID();
        ChatMessageResourceEntity resource = new ChatMessageResourceEntity();
        resource.setId(resourceId);
        resource.setMessageId(null);
        resource.setUserId(user.getId());
        resource.setResourceType("FILE");
        resource.setResourceRole("ATTACHMENT");
        resource.setStorageType("LOCAL_FILE");
        resource.setStorageKey("uploads/" + resourceId);
        resource.setViewUrl("/api/chat/resources/" + resourceId + "/content");
        resource.setDownloadUrl("/api/chat/resources/" + resourceId + "/download");
        resource.setMimeType("text/plain");
        resource.setFileName("evidence.txt");
        resource.setFileSize(8L);
        resource.setCreatedAt(LocalDateTime.now());
        chatMessageResourceMapper.insert(resource);

        var turn = harnessCollaborationService.beginSubagentTurn(
                user.getId(), rootSessionId, childSessionId, "请结合附件继续分析。",
                java.util.List.of(new ChatMessageResourceUseDto(resourceId, "REFERENCE", "UPLOAD"))
        );

        ChatMessageResourceEntity bound = chatMessageResourceMapper.selectByResourceId(resourceId);
        assertEquals(turn.userMessageId(), bound.getMessageId());
        var thread = chatSessionService.getSessionMessages(user.getId(), childSessionId, 20, null);
        assertEquals(resourceId, thread.messages().getLast().resources().getFirst().id());
    }

    private UserEntity createUser() {
        LocalDateTime now = LocalDateTime.now();
        UserEntity user = new UserEntity();
        user.setEmail("harness_open_" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("hash-value");
        user.setStatus((short) 1);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userMapper.insert(user);
        return user;
    }
}
