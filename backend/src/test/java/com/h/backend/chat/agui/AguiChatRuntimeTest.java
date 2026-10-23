package com.h.backend.chat.agui;

import com.h.backend.automation.application.AutomationProposalModule;
import com.h.backend.chat.application.*;
import com.h.backend.chat.application.agui.AguiChatRuntime;
import com.h.backend.chat.domain.agent.HarnessExecutionRegistry;
import com.h.backend.chat.domain.agent.HarnessSubagentEventRelay;
import com.h.backend.chat.infrastructure.persistence.entity.AgentSessionEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.*;
import com.h.backend.chat.interfaces.dto.*;
import com.h.backend.common.exception.BusinessException;
import com.agui.community.core.message.UserMessage;
import com.agui.community.core.agent.RunAgentInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AguiChatRuntimeTest {
    ChatService chat = mock(ChatService.class);
    ChatSessionService sessions = mock(ChatSessionService.class);
    HarnessApprovalService approvals = mock(HarnessApprovalService.class);
    ApprovalRequestService approvalRequests = mock(ApprovalRequestService.class);
    AutomationProposalModule proposals = mock(AutomationProposalModule.class);
    AgentSessionMapper agentSessions = mock(AgentSessionMapper.class);
    AguiRunSnapshotMapper snapshots = mock(AguiRunSnapshotMapper.class);
    AgentRunService runService = mock(AgentRunService.class);
    ApprovalRequestMapper approvalRows = mock(ApprovalRequestMapper.class);
    HarnessExecutionRegistry registry = spy(new HarnessExecutionRegistry());
    HarnessSubagentMapper childRows = mock(HarnessSubagentMapper.class);
    Map<String, AguiRunSnapshotMapper.Snapshot> rows = new ConcurrentHashMap<>();
    AguiChatRuntime runtime;

    @BeforeEach
    void setup() {
        runtime = new AguiChatRuntime(chat, sessions, approvals, approvalRequests, proposals, agentSessions,
                snapshots, runService, approvalRows, JsonMapper.builder().build(),
                registry, childRows);
        AgentSessionEntity root = session("root", null, 1L);
        when(agentSessions.selectBySessionId("root")).thenReturn(root);
        when(agentSessions.selectChildren(anyString())).thenReturn(List.of());
        when(sessions.getSessionDetail(1L, "root")).thenReturn(new ChatSessionMetaDto("root", "title", 2L,
                "standard-chat", "助手", "通用", "STANDARD_STREAMING_CHAT", null, 0, null, null, false));
        when(sessions.getSessionMessages(anyLong(), anyString(), anyInt(), isNull())).thenAnswer(invocation ->
                new ChatSessionMessagesPageDto(invocation.getArgument(1), List.of(), false, null));
        when(snapshots.find(anyLong(), anyString())).thenAnswer(invocation -> {
            var row = rows.get(invocation.getArgument(1));
            return row != null && row.userId().equals(invocation.getArgument(0)) ? row : null;
        });
        when(snapshots.list(anyLong(), anyString())).thenAnswer(invocation -> rows.values().stream()
                .filter(row -> row.userId().equals(invocation.getArgument(0)) && row.sessionId().equals(invocation.getArgument(1))).toList());
        doAnswer(invocation -> { var row = (AguiRunSnapshotMapper.Snapshot) invocation.getArgument(0); rows.put(row.runId(), row); return null; })
                .when(snapshots).insert(any());
        doAnswer(invocation -> {
            String id = invocation.getArgument(0);
            var row = rows.get(id);
            rows.put(id, new AguiRunSnapshotMapper.Snapshot(id, row.sessionId(), row.userId(), row.requestHash(),
                    invocation.getArgument(2), invocation.getArgument(3), row.createdAt()));
            return null;
        }).when(snapshots).update(anyString(), anyLong(), anyString(), anyString());
        when(proposals.listAnchoredForSession(anyLong(), anyString())).thenReturn(List.of());
    }

    @AfterEach
    void cleanup() { runtime.stop(1L, "root", null); }

    @Test
    void disconnectOnlyDetachesObserverAndRunStillCommits() throws Exception {
        Sinks.Many<ChatStreamEvent> source = Sinks.many().unicast().onBackpressureBuffer();
        AtomicBoolean cancelled = new AtomicBoolean();
        CountDownLatch subscribed = new CountDownLatch(1);
        when(chat.streamChat(eq(1L), eq(2L), eq("standard-chat"), eq("root"), eq("hello"), anyList()))
                .thenReturn(source.asFlux().doOnSubscribe(ignored -> subscribed.countDown()).doOnCancel(() -> cancelled.set(true)));
        Disposable observer = runtime.run(1L, input("run-1", "root", "hello")).subscribe();
        assertTrue(subscribed.await(2, TimeUnit.SECONDS));
        observer.dispose();
        assertFalse(cancelled.get());
        source.tryEmitNext(new ChatStreamEvent("chunk", "answer"));
        source.tryEmitNext(new ChatStreamEvent("done", ""));
        assertEquals("COMPLETED", rows.get("run-1").status());
        assertFalse(cancelled.get());
    }

    @Test
    void duplicateRunIdReattachesAndCannotSubmitADifferentMessage() {
        when(chat.streamChat(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyList())).thenReturn(Flux.never());
        runtime.run(1L, input("run-1", "root", "hello"));
        runtime.run(1L, input("run-1", "root", "hello"));
        verify(chat, times(1)).streamChat(anyLong(), anyLong(), anyString(), anyString(), anyString(), anyList());
        assertThrows(BusinessException.class, () -> runtime.run(1L, input("run-1", "root", "different")));
    }

    @Test
    void refusesForeignThreadsBeforeStartingAnyExecution() {
        when(agentSessions.selectBySessionId("foreign")).thenReturn(session("foreign", null, 9L));
        assertThrows(BusinessException.class, () -> runtime.run(1L, input("run-1", "foreign", "hello")));
        assertThrows(BusinessException.class, () -> runtime.stop(1L, "foreign", null));
        verifyNoInteractions(chat);
    }

    @Test
    void parentStopCascadesButChildStopDoesNotTouchSiblingsOrParent() {
        AgentSessionEntity child = session("child", "root", 1L);
        when(agentSessions.selectBySessionId("child")).thenReturn(child);
        when(agentSessions.selectChildren("root")).thenReturn(List.of(child));
        runtime.stop(1L, "child", null);
        verify(registry).stop("1", "child");
        verify(registry, never()).stop("1", "root");
        clearInvocations(registry);
        runtime.stop(1L, "root", null);
        verify(registry).stop("1", "child");
        verify(registry).stop("1", "root");
    }

    static RunAgentInput input(String runId, String thread, String message) {
        return new RunAgentInput(thread, runId, null, List.of(new UserMessage("user-1", message)), List.of(), List.of(), Map.of());
    }

    @Test
    void restoredReasoningAndResourcesKeepTheirOriginalTurnPositions() {
        var history = List.of(
                new ChatSessionMessageDto("u1", "user", "USER", "first", null, List.of(), null),
                new ChatSessionMessageDto("r1", "assistant", "REASONING", "thinking", null, List.of(), null),
                new ChatSessionMessageDto("i1", "assistant", "IMAGE", "", null, List.of(
                        new ChatMessageResourceDto("image", "IMAGE", "OUTPUT", "/view", "/download", "image.png",
                                "image/png", 1L, null, null, null, null)), null),
                new ChatSessionMessageDto("a1", "assistant", "AI", "first answer", null, List.of(), null),
                new ChatSessionMessageDto("u2", "user", "USER", "second", null, List.of(), null),
                new ChatSessionMessageDto("r2", "assistant", "REASONING", "next thinking", null, List.of(), null),
                new ChatSessionMessageDto("a2", "assistant", "AI", "second answer", null, List.of(), null));
        when(sessions.getSessionMessages(1L, "root", 100, null))
                .thenReturn(new ChatSessionMessagesPageDto("root", history, false, null));
        var input = new RunAgentInput("root", "observe", null, List.of(), List.of(), List.of(), Map.of("observe", true));
        var events = runtime.run(1L, input).collectList().block(java.time.Duration.ofSeconds(3));
        var cards = events.stream().filter(com.agui.community.core.event.ActivitySnapshotEvent.class::isInstance)
                .map(com.agui.community.core.event.ActivitySnapshotEvent.class::cast)
                .collect(java.util.stream.Collectors.toMap(com.agui.community.core.event.ActivitySnapshotEvent::messageId,
                        card -> (Map<?, ?>) card.content()));
        // The old UI displays generated images, then the reasoning attached to the final answer.
        assertEquals("a1", cards.get("reasoning-r1").get("anchorMessageId"));
        assertEquals("BEFORE", cards.get("reasoning-r1").get("anchorPlacement"));
        assertEquals("i1", cards.get("resource-i1").get("anchorMessageId"));
        assertEquals("AFTER", cards.get("resource-i1").get("anchorPlacement"));
        assertEquals("a2", cards.get("reasoning-r2").get("anchorMessageId"));
    }

    @Test
    void pendingChildApprovalIsIdleAndCanBeStoppedAfterTheTransportHasClosed() {
        when(agentSessions.selectBySessionId("child")).thenReturn(session("child", "root", 1L));
        var child = new com.h.backend.chat.infrastructure.persistence.entity.HarnessSubagentEntity();
        child.setSessionId("child"); child.setStatus("RUNNING"); child.setExecutionId("execution");
        when(childRows.selectBySessionId("child")).thenReturn(child);
        var pending = new ApprovalRequestDto("approval", 7L, "root", "child", "execution",
                com.h.backend.chat.domain.approval.ApprovalMode.DEFAULT, List.of(),
                com.h.backend.chat.domain.approval.ApprovalRequestStatus.PENDING, null, 0, null, null);
        when(approvalRequests.findPending(1L, "child")).thenReturn(pending);
        rows.put("waiting", new AguiRunSnapshotMapper.Snapshot("waiting", "child", 1L, "hash", "WAITING_APPROVAL",
                "{\"status\":\"WAITING_APPROVAL\",\"activities\":[]}", null));
        assertEquals(false, runtime.thread(1L, "child").get("executing"));
        runtime.stop(1L, "child", null);
        assertEquals("CANCELLED", rows.get("waiting").status());
        verify(childRows).failExecution("child", "execution", "CANCELLED", "用户已停止任务");
        verify(runService).failRun(7L, "用户已停止任务");
    }

    @Test
    @SuppressWarnings("unchecked")
    void approvalResumeUsesTheExistingApprovalAndRefreshesTheSuspendedCard() {
        var pending = new ApprovalRequestDto("approval", 7L, "root", "root", null,
                com.h.backend.chat.domain.approval.ApprovalMode.DEFAULT, List.of(),
                com.h.backend.chat.domain.approval.ApprovalRequestStatus.PENDING, null, 0, null, null);
        var decided = new ApprovalRequestDto("approval", 7L, "root", "root", null,
                com.h.backend.chat.domain.approval.ApprovalMode.DEFAULT, List.of(),
                com.h.backend.chat.domain.approval.ApprovalRequestStatus.APPROVED,
                com.h.backend.chat.domain.approval.ApprovalDecision.APPROVE, 1, null, null);
        var current = new java.util.concurrent.atomic.AtomicReference<>(pending);
        when(approvalRequests.getOwned(1L, "approval")).thenAnswer(ignored -> current.get());
        when(approvals.decideAndResume(1L, "approval", com.h.backend.chat.domain.approval.ApprovalDecision.APPROVE))
                .thenReturn(Flux.defer(() -> {
                    current.set(decided);
                    return Flux.just(new ChatStreamEvent("done", ""));
                }));
        rows.put("old", new AguiRunSnapshotMapper.Snapshot("old", "root", 1L, "hash", "WAITING_APPROVAL",
                "{\"runId\":\"old\",\"status\":\"WAITING_APPROVAL\",\"activities\":[{\"id\":\"approval-approval\",\"activityType\":\"h-agent.approval\",\"content\":{\"approval\":{\"approvalId\":\"approval\"}}}]}", null));
        var input = new RunAgentInput("root", "resume", null, List.of(), List.of(), List.of(), Map.of("approvalId", "approval", "decision", "APPROVE"));
        var events = runtime.run(1L, input).collectList().block(java.time.Duration.ofSeconds(3));
        assertTrue(events.stream().anyMatch(com.agui.community.core.event.RunFinishedEvent.class::isInstance));
        assertEquals("COMPLETED", rows.get("resume").status());
        var historical = (List<Map<String, Object>>) runtime.thread(1L, "root").get("runs");
        assertEquals("RESUMED", historical.stream().filter(run -> "old".equals(run.get("runId"))).findFirst().orElseThrow().get("status"));
        verifyNoInteractions(chat);
        verify(approvals).decideAndResume(1L, "approval", com.h.backend.chat.domain.approval.ApprovalDecision.APPROVE);
    }

    static AgentSessionEntity session(String id, String parent, Long owner) {
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(id); session.setParentSessionId(parent); session.setUserId(owner); session.setAgentId("standard-chat");
        return session;
    }
}
