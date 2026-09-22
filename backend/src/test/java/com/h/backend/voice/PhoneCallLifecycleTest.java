package com.h.backend.voice;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.SystemPromptService;
import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.ChatStreamConcurrencyGuard;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.interfaces.dto.ChatSessionMetaDto;
import com.h.backend.chat.interfaces.dto.ChatSessionOpenDto;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.voice.application.VoiceCallModule;
import com.h.backend.voice.application.VoiceReply;
import com.h.backend.voice.application.VoiceTurnModule;
import com.h.backend.voice.domain.VoiceCall;
import com.h.backend.voice.infrastructure.LiveKitGateway;
import com.h.backend.voice.infrastructure.VoiceProperties;
import com.h.backend.voice.infrastructure.VoiceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PhoneCallLifecycleTest {

    private VoiceStore store;
    private VoiceProperties properties;
    private LiveKitGateway livekit;
    private ChatSessionService sessions;
    private SystemPromptService prompts;
    private AgentRunService runs;
    private ChatStreamConcurrencyGuard guard;
    private VoiceReply reply;
    private org.springframework.beans.factory.ObjectProvider<VoiceTurnModule> turns;
    private VoiceTurnModule turnModule;
    private VoiceCallModule module;

    @BeforeEach
    void setup() {
        store = mock(VoiceStore.class);
        properties = new VoiceProperties();
        livekit = mock(LiveKitGateway.class);
        sessions = mock(ChatSessionService.class);
        prompts = mock(SystemPromptService.class);
        runs = mock(AgentRunService.class);
        guard = mock(ChatStreamConcurrencyGuard.class);
        reply = mock(VoiceReply.class);
        turns = mock(org.springframework.beans.factory.ObjectProvider.class);
        turnModule = mock(VoiceTurnModule.class);
        when(turns.getObject()).thenReturn(turnModule);
        module = new VoiceCallModule(store, properties, livekit, sessions, prompts, runs, guard, reply, turns);

        when(reply.modelName()).thenReturn("test-model");
    }

    // ── PHONE 创建不调用 LiveKit ──

    @Test
    void createPhoneCall_doesNotInvokeLiveKit() {
        String requestId = UUID.randomUUID().toString();
        when(store.byRequest(anyLong(), eq(requestId))).thenReturn(null);
        when(sessions.createPhoneSession(eq(1L), isNull(), eq("standard-chat")))
                .thenReturn(new ChatSessionOpenDto(
                        new ChatSessionMetaDto("sess-1", "外呼会话", 1L, "standard-chat",
                                "Chat", "CHAT", null, null, 0, null, null, false),
                        null, null));
        when(sessions.getSessionDetail(eq(1L), eq("sess-1")))
                .thenReturn(new ChatSessionMetaDto("sess-1", "外呼会话", 1L, "standard-chat",
                        "Chat", "CHAT", null, null, 0, null, null, false));
        when(prompts.getSystemPrompt(eq(1L), eq(1L))).thenReturn("system prompt");

        var result = module.createPhoneCall(1L, "standard-chat", null, requestId);

        assertNotNull(result.get("callId"));
        assertEquals("PHONE", result.get("channel"));
        assertEquals("PREPARING", result.get("state"));
        verify(livekit, never()).dispatch(any());
        verify(livekit, never()).participantToken(any());
    }

    @Test
    void createHarnessPhoneCall_doesNotRequireOrdinaryPrompt() {
        String requestId = UUID.randomUUID().toString();
        when(store.byRequest(anyLong(), eq(requestId))).thenReturn(null);
        when(sessions.createPhoneSession(eq(1L), isNull(), eq(ChatAgentIds.HARNESS)))
                .thenReturn(new ChatSessionOpenDto(
                        new ChatSessionMetaDto("sess-harness", "外呼会话", null, ChatAgentIds.HARNESS,
                                "Harness", "CHAT", null, null, 0, null, null, false),
                        null, null));
        when(sessions.getSessionDetail(1L, "sess-harness"))
                .thenReturn(new ChatSessionMetaDto("sess-harness", "外呼会话", null, ChatAgentIds.HARNESS,
                        "Harness", "CHAT", null, null, 0, null, null, false));

        var result = module.createPhoneCall(
                1L, ChatAgentIds.HARNESS, null, requestId, "确认客户预约时间");

        assertEquals("PHONE", result.get("channel"));
        verify(prompts, never()).getSystemPrompt(anyLong(), any());
        verify(store).insert(argThat((VoiceCall call) ->
                call.getPromptId() == null
                        && call.getSystemPrompt().contains("确认客户预约时间")));
    }

    // ── 重复准备复用同一资源 ──

    @Test
    void createPhoneCall_duplicateRequestId_returnsExisting() {
        String requestId = UUID.randomUUID().toString();
        VoiceCall existing = new VoiceCall();
        existing.setChannel("PHONE");
        existing.setId("call-existing");
        existing.setRequestId(requestId);
        existing.setSessionId("sess-existing");
        existing.setState("PREPARING");
        when(store.byRequest(anyLong(), eq(requestId))).thenReturn(existing);

        var result = module.createPhoneCall(1L, "standard-chat", null, requestId);

        assertEquals("call-existing", result.get("callId"));
        assertEquals("PHONE", result.get("channel"));
        verify(sessions, never()).createPhoneSession(anyLong(), any(), any());
    }

    // ── 旧 Worker 的提交被拒绝 ──

    @Test
    void oldWorkerSubmissionRejected() {
        VoiceCall call = new VoiceCall();
        call.setChannel("PHONE");
        call.setId("call-1");
        call.setWorkerId("worker-old");
        call.setWorkerEpoch(1);
        call.setLeaseUntil(System.currentTimeMillis() - 1000);
        call.setState("ACTIVE");

        assertThrows(BusinessException.class, () -> VoiceCallModule.worker(call, 1),
                "old worker epoch should be rejected after lease expiry");
    }

    @Test
    void newWorkerEpochAccepted() {
        VoiceCall call = new VoiceCall();
        call.setChannel("PHONE");
        call.setId("call-1");
        call.setWorkerId("worker-new");
        call.setWorkerEpoch(2);
        call.setLeaseUntil(System.currentTimeMillis() + 30000);
        call.setState("ACTIVE");

        assertDoesNotThrow(() -> VoiceCallModule.worker(call, 2));
    }

    // ── PHONE 创建不归档其他会话 ──

    @Test
    void createPhoneCall_doesNotArchiveOtherSessions() {
        String requestId = UUID.randomUUID().toString();
        when(store.byRequest(anyLong(), eq(requestId))).thenReturn(null);
        when(sessions.createPhoneSession(eq(1L), isNull(), eq("standard-chat")))
                .thenReturn(new ChatSessionOpenDto(
                        new ChatSessionMetaDto("sess-1", "外呼会话", 1L, "standard-chat",
                                "Chat", "CHAT", null, null, 0, null, null, false),
                        null, null));
        when(sessions.getSessionDetail(eq(1L), eq("sess-1")))
                .thenReturn(new ChatSessionMetaDto("sess-1", "外呼会话", 1L, "standard-chat",
                        "Chat", "CHAT", null, null, 0, null, null, false));
        when(prompts.getSystemPrompt(eq(1L), eq(1L))).thenReturn("system prompt");

        module.createPhoneCall(1L, "standard-chat", null, requestId);

        verify(sessions, never()).createSession(anyLong(), any(), any(), any(), any());
        verify(sessions, never()).chooseActiveSession(anyLong(), any());
        verify(sessions, never()).activateHistorySession(anyLong(), any(), any());
    }

    // ── PHONE claim 不检查 room_name ──

    @Test
    void claimPhoneCall_ignoresRoomName() {
        VoiceCall call = new VoiceCall();
        call.setChannel("PHONE");
        call.setId("call-1");
        call.setClaimSecret("secret-123");
        call.setState("PREPARING");
        call.setLeaseUntil(0);

        when(store.get("call-1")).thenReturn(call);
        when(store.locked(eq("call-1"), any())).thenAnswer(inv -> {
            var fn = inv.getArgument(1, java.util.function.Function.class);
            return fn.apply(call);
        });

        var result = module.claim("call-1", "any-room", "secret-123", "worker-1");
        assertEquals("worker-1", call.getWorkerId());
        assertEquals(1, call.getWorkerEpoch());
    }

    // ── PHONE 接听后 + 媒体就绪 = ACTIVE ──

    @Test
    void phoneAnswered_plusWorkerReady_equalsActive() {
        VoiceCall call = new VoiceCall();
        call.setChannel("PHONE");
        call.setId("call-1");
        call.setState("PREPARING");
        call.setWorkerId("worker-1");
        call.setWorkerEpoch(1);
        call.setLeaseUntil(System.currentTimeMillis() + 30000);
        call.setClaimSecret("secret-123");

        when(store.get("call-1")).thenReturn(call);
        when(store.locked(eq("call-1"), any())).thenAnswer(inv -> {
            var fn = inv.getArgument(1, java.util.function.Function.class);
            return fn.apply(call);
        });

        module.setPhoneAnswered("call-1");
        assertTrue(call.isParticipantJoined());
        assertNotEquals("ACTIVE", call.getState());

        call.setWorkerReady(true);
        module.setPhoneAnswered("call-1");
        assertEquals("ACTIVE", call.getState());
    }

    @Test
    void phoneCleanup_waitsForWorkerTerminationFact() {
        VoiceCall call = new VoiceCall();
        call.setChannel("PHONE");
        call.setId("call-1");
        call.setState("ENDING");
        call.setReason("USER_HANGUP");
        call.setWorkerEnded(false);
        when(store.get("call-1")).thenReturn(call);
        when(store.openTurn("call-1")).thenReturn(null);
        when(store.locked(eq("call-1"), any())).thenAnswer(inv -> {
            var fn = inv.getArgument(1, java.util.function.Function.class);
            return fn.apply(call);
        });

        module.cleanup("call-1");

        assertEquals("ENDING", call.getState());
    }

    @Test
    void recoveryConfirmsTerminationWhenPhoneWorkerWasNeverClaimed() {
        VoiceCall call = new VoiceCall();
        call.setChannel("PHONE");
        call.setId("call-unclaimed");
        call.setState("PREPARING");
        when(store.pending()).thenReturn(List.of(call));
        when(store.openTurn("call-unclaimed")).thenReturn(null);
        when(store.locked(eq("call-unclaimed"), any())).thenAnswer(inv -> {
            var fn = inv.getArgument(1, java.util.function.Function.class);
            return fn.apply(call);
        });

        module.recover();

        assertTrue(call.isWorkerEnded());
        assertEquals("ENDING", call.getState());
        verify(turnModule).finishPending("call-unclaimed");
    }

    @Test
    void operatorConfirmationInvalidatesLostPhoneWorkerAndAllowsCleanup() {
        VoiceCall call = new VoiceCall();
        call.setChannel("PHONE");
        call.setId("call-lost");
        call.setState("ACTIVE");
        call.setWorkerId("worker-1");
        call.setWorkerEpoch(3);
        call.setLeaseUntil(System.currentTimeMillis() + 30_000);
        when(store.get("call-lost")).thenReturn(call);
        when(store.openTurn("call-lost")).thenReturn(null);
        when(store.locked(eq("call-lost"), any())).thenAnswer(inv -> {
            var fn = inv.getArgument(1, java.util.function.Function.class);
            return fn.apply(call);
        });

        module.confirmPhoneWorkerEnded("call-lost", "OPERATOR_CONFIRMED_ENDED");

        assertTrue(call.isWorkerEnded());
        assertEquals(4, call.getWorkerEpoch());
        assertEquals(0, call.getLeaseUntil());
        assertEquals("FAILED", call.getState());
        verify(turnModule).stopCall("call-lost");
    }
}
