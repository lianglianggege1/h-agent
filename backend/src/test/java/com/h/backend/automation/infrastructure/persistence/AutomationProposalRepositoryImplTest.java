package com.h.backend.automation.infrastructure.persistence;

import com.h.backend.automation.application.AnchoredProposalView;
import com.h.backend.automation.infrastructure.persistence.mapper.AutomationProposalMapper;
import com.h.backend.automation.infrastructure.persistence.mapper.AutomationProposalMapper.AnchoredProposalRow;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 提案锚点解析测试（实施文档 7.1 场景 3/4/5/7/8/9）。
 * 锚点由终态 AgentRun 的不可变消息关系决定；未终结的 Run 不发布锚点；
 * 所有权或 Session 不一致时作为数据损坏拒绝返回。
 */
class AutomationProposalRepositoryImplTest {

    private static final Long USER = 7L;
    private static final String SESSION = "session-1";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 10, 0, 0, 0);

    private List<AnchoredProposalView> resolve(AnchoredProposalRow... rows) {
        AutomationProposalMapper mapper = mock(AutomationProposalMapper.class);
        when(mapper.selectAnchoredBySession(USER, SESSION)).thenReturn(List.of(rows));
        return new AutomationProposalRepositoryImpl(mapper).listAnchoredBySession(USER, SESSION);
    }

    @Test
    void succeededRunAnchorsToAssistantMessage() {
        List<AnchoredProposalView> views = resolve(
                row("p1", USER, SESSION, 51L, "SUCCEEDED", 1001L, 2048L, SESSION));

        assertEquals(1, views.size());
        assertEquals("2048", views.getFirst().anchorMessageId());
        assertEquals(51L, views.getFirst().sourceAgentRunId());
        assertEquals(AnchoredProposalView.PLACEMENT_AFTER, views.getFirst().anchorPlacement());
    }

    @Test
    void succeededRunWithoutAssistantMessageAnchorsToUserMessage() {
        List<AnchoredProposalView> views = resolve(
                row("p1", USER, SESSION, 51L, "SUCCEEDED", 1001L, null, SESSION));

        assertEquals("1001", views.getFirst().anchorMessageId());
    }

    @Test
    void failedRunAnchorsToUserMessage() {
        List<AnchoredProposalView> views = resolve(
                row("p1", USER, SESSION, 51L, "FAILED", 1001L, 2048L, SESSION));

        assertEquals("1001", views.getFirst().anchorMessageId());
    }

    @Test
    void cancelledRunAnchorsToUserMessage() {
        List<AnchoredProposalView> views = resolve(
                row("p1", USER, SESSION, 51L, "CANCELLED", 1001L, null, SESSION));

        assertEquals("1001", views.getFirst().anchorMessageId());
    }

    @Test
    void runningRunIsNotPublished() {
        List<AnchoredProposalView> views = resolve(
                row("p1", USER, SESSION, 51L, "RUNNING", 1001L, null, SESSION));

        assertEquals(1, views.size());
        assertNull(views.getFirst().anchorMessageId());
    }

    @Test
    void waitingApprovalRunIsNotPublished() {
        List<AnchoredProposalView> views = resolve(
                row("p1", USER, SESSION, 51L, "WAITING_APPROVAL", 1001L, null, SESSION));

        assertNull(views.getFirst().anchorMessageId());
    }

    @Test
    void proposalWithoutRunHasNoAnchor() {
        List<AnchoredProposalView> views = resolve(
                row("p1", USER, SESSION, null, null, null, null, null));

        assertNull(views.getFirst().anchorMessageId());
        assertNull(views.getFirst().sourceAgentRunId());
    }

    @Test
    void multipleProposalsKeepMapperOrder() {
        // mapper 已按 created_at ASC, id ASC 排序；repository 必须原样保留顺序，不得重排。
        List<AnchoredProposalView> views = resolve(
                row("p1", USER, SESSION, 51L, "SUCCEEDED", 1001L, 2048L, SESSION),
                row("p2", USER, SESSION, 51L, "SUCCEEDED", 1001L, 2048L, SESSION));

        assertEquals(2, views.size());
        assertEquals("p1", views.get(0).proposal().id());
        assertEquals("p2", views.get(1).proposal().id());
        assertEquals("2048", views.get(0).anchorMessageId());
        assertEquals("2048", views.get(1).anchorMessageId());
    }

    @Test
    void otherUsersProposalIsRejected() {
        List<AnchoredProposalView> views = resolve(
                row("p1", 999L, SESSION, 51L, "SUCCEEDED", 1001L, 2048L, SESSION));

        assertTrue(views.isEmpty());
    }

    @Test
    void otherSessionProposalIsRejected() {
        List<AnchoredProposalView> views = resolve(
                row("p1", USER, "session-other", 51L, "SUCCEEDED", 1001L, 2048L, "session-other"));

        assertTrue(views.isEmpty());
    }

    @Test
    void runSessionMismatchIsRejected() {
        // 提案属于 session-1，但关联 Run 属于另一个 Session：视为数据损坏，拒绝返回锚点。
        List<AnchoredProposalView> views = resolve(
                row("p1", USER, SESSION, 51L, "SUCCEEDED", 1001L, 2048L, "session-tampered"));

        assertTrue(views.isEmpty());
    }

    private static AnchoredProposalRow row(
            String id,
            Long userId,
            String sourceSessionId,
            Long sourceAgentRunId,
            String runStatus,
            Long runUserMessageId,
            Long runAssistantMessageId,
            String runSessionId
    ) {
        AnchoredProposalRow row = new AnchoredProposalRow();
        row.setId(id);
        row.setUserId(userId);
        row.setTaskId(null);
        row.setAction("CREATE");
        row.setPayloadJson("{\"name\":\"晨报\",\"instruction\":\"汇总\",\"agentId\":\"standard-chat\","
                + "\"runtime\":\"LANGCHAIN4J\",\"cronExpression\":\"0 0 9 * * *\","
                + "\"zoneId\":\"Asia/Shanghai\",\"deliverySink\":\"NONE\"}");
        row.setBaseTaskRevision(null);
        row.setStatus("PENDING");
        row.setSourceSessionId(sourceSessionId);
        row.setCreatedVia("CHAT");
        row.setIdempotencyKey("key-" + id);
        row.setResultTaskId(null);
        row.setSourceAgentRunId(sourceAgentRunId);
        row.setExpiresAt(NOW.plusSeconds(3600));
        row.setConfirmedAt(null);
        row.setConfirmedBy(null);
        row.setCreatedAt(NOW);
        row.setUpdatedAt(NOW);
        row.setRunStatus(runStatus);
        row.setRunUserMessageId(runUserMessageId);
        row.setRunAssistantMessageId(runAssistantMessageId);
        row.setRunSessionId(runSessionId);
        row.setRunUserId(userId);
        return row;
    }
}
