package com.h.backend.outbound;

import com.h.backend.outbound.application.OutboundModule;
import com.h.backend.outbound.domain.Call;
import com.h.backend.outbound.domain.Task;
import com.h.backend.outbound.infrastructure.OutboundProperties;
import com.h.backend.outbound.infrastructure.OutboundStore;
import com.h.backend.outbound.infrastructure.DialingProvider;
import com.h.backend.outbound.infrastructure.PhoneWorkerClient;
import com.h.backend.voice.application.VoiceCallModule;
import com.h.backend.voice.domain.VoiceCall;
import com.h.backend.voice.infrastructure.VoiceStore;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OutboundRecoveryTest {

    private OutboundStore store;
    private OutboundProperties properties;
    private OutboundModule module;

    @BeforeEach
    void setup() {
        store = mock(OutboundStore.class);
        properties = new OutboundProperties();
        properties.setEnabled(true);
        properties.setInternalToken("0123456789abcdef0123456789abcdef");
        properties.setMediaVerified(true);
        module = new OutboundModule(store, properties);
    }

    @SuppressWarnings("unchecked")
    private void mockLockedCall(Call call) {
        when(store.lockedCall(eq(call.getId()), any())).thenAnswer(inv -> {
            var fn = inv.getArgument(1, Function.class);
            return fn.apply(call);
        });
        when(store.transaction(any())).thenAnswer(inv -> {
            var fn = inv.getArgument(0, Supplier.class);
            return fn.get();
        });
    }

    // ── UNKNOWN resolve: manual close ──

    @Test
    void resolveUnknown_manualResolved_movesToFinished() {
        Call call = new Call();
        call.setId(1L);
        call.setUserId(1L);
        call.setStage("UNKNOWN");
        when(store.getOwnedCall(1L, 1L)).thenReturn(call);
        mockLockedCall(call);

        var result = module.resolveUnknown(1L, 1L, "ANSWERED", "用户确认已接通");

        assertEquals("FINISHED", call.getStage());
        assertEquals("CONNECTED", call.getConnectResult());
        assertEquals("UNKNOWN", call.getDialogueResult());
        assertTrue(result.containsKey("resolved"));
    }

    @Test
    void resolveUnknown_nonUnknownCall_throws() {
        Call call = new Call();
        call.setId(1L);
        call.setUserId(1L);
        call.setStage("FINISHED");
        when(store.getOwnedCall(1L, 1L)).thenReturn(call);

        assertThrows(com.h.backend.common.exception.BusinessException.class,
                () -> module.resolveUnknown(1L, 1L, "ANSWERED", "note"));
    }

    @Test
    void resolveUnknown_missingResolution_throws() {
        Call call = new Call();
        call.setId(1L);
        call.setUserId(1L);
        call.setStage("UNKNOWN");
        when(store.getOwnedCall(1L, 1L)).thenReturn(call);

        assertThrows(com.h.backend.common.exception.BusinessException.class,
                () -> module.resolveUnknown(1L, 1L, null, "note"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveUnknown_canRecordOperatorConfirmationForLostPhoneWorker() {
        VoiceCallModule voiceCalls = mock(VoiceCallModule.class);
        VoiceStore voiceStore = mock(VoiceStore.class);
        ObjectProvider<PhoneWorkerClient> workerProvider = mock(ObjectProvider.class);
        ObjectProvider<DialingProvider> dialingProvider = mock(ObjectProvider.class);
        ObjectProvider<com.h.backend.chat.domain.agent.AgentRegistry> registryProvider = mock(ObjectProvider.class);
        DialingProvider dialer = mock(DialingProvider.class);
        Environment environment = mock(Environment.class);
        module = new OutboundModule(store, properties, voiceCalls, voiceStore,
                workerProvider, dialingProvider, registryProvider, environment);

        Call call = new Call();
        call.setId(1L);
        call.setUserId(1L);
        call.setVoiceCallId("voice-lost");
        call.setStage("UNKNOWN");
        VoiceCall live = mock(VoiceCall.class);
        VoiceCall ended = mock(VoiceCall.class);
        when(live.terminal()).thenReturn(false);
        when(ended.terminal()).thenReturn(true);
        when(store.getOwnedCall(1L, 1L)).thenReturn(call);
        when(dialingProvider.getIfAvailable()).thenReturn(dialer);
        when(dialer.query("voice-lost")).thenReturn(DialingProvider.RemoteCallState.ABSENT);
        when(voiceStore.get("voice-lost")).thenReturn(live, ended);
        mockLockedCall(call);

        module.resolveUnknown(1L, 1L, "FAILED", "人工确认 Worker 已停止");

        verify(voiceCalls).confirmPhoneWorkerEnded("voice-lost", "OPERATOR_CONFIRMED_ENDED");
        assertEquals("FINISHED", call.getStage());
        assertEquals("FAILED", call.getConnectResult());
    }

    // ── Task start: COMPLETED cannot restart ──

    @Test
    void startTask_completedTask_cannotRestart() {
        Task task = new Task();
        task.setId(1L);
        task.setStatus("COMPLETED");
        when(store.getTask(1L, 1L)).thenReturn(task);
        when(store.hasAnyActiveCall()).thenReturn(false);
        when(store.hasAnyRunningTask()).thenReturn(false);
        when(store.lockedTask(eq(1L), any())).thenAnswer(inv -> {
            var fn = inv.getArgument(1, Function.class);
            return fn.apply(task);
        });

        var ex = assertThrows(com.h.backend.common.exception.BusinessException.class,
                () -> module.startTask(1L, 1L));
        assertTrue(ex.getMessage().contains("已完成"));
    }

    // ── Stop cancels queued calls ──

    @Test
    void stopTask_cancelsQueuedCalls() {
        Task task = new Task();
        task.setId(1L);
        task.setStatus("RUNNING");
        when(store.getTask(1L, 1L)).thenReturn(task);
        when(store.lockedTask(eq(1L), any())).thenAnswer(inv -> {
            var fn = inv.getArgument(1, Function.class);
            return fn.apply(task);
        });
        when(store.transaction(any())).thenAnswer(inv -> {
            var fn = inv.getArgument(0, Supplier.class);
            return fn.get();
        });

        module.stopTask(1L, 1L);

        assertEquals("STOPPED", task.getStatus());
        verify(store).cancelQueuedCalls(1L);
    }

    // ── Reconcile on non-unknown call ──

    @Test
    void reconcileCall_finishedCall_noop() {
        Call call = new Call();
        call.setId(1L);
        call.setUserId(1L);
        call.setStage("FINISHED");
        when(store.getOwnedCall(1L, 1L)).thenReturn(call);

        var result = module.reconcileCall(1L, 1L);

        assertEquals(false, result.get("reconciled"));
        assertEquals("FINISHED", result.get("stage"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void reconcileCall_releasesUnknownOnlyWhenRemoteAndLocalAreEnded() {
        VoiceCallModule voiceCalls = mock(VoiceCallModule.class);
        VoiceStore voiceStore = mock(VoiceStore.class);
        ObjectProvider<PhoneWorkerClient> workerProvider = mock(ObjectProvider.class);
        ObjectProvider<DialingProvider> dialingProvider = mock(ObjectProvider.class);
        ObjectProvider<com.h.backend.chat.domain.agent.AgentRegistry> registryProvider = mock(ObjectProvider.class);
        DialingProvider dialer = mock(DialingProvider.class);
        Environment environment = mock(Environment.class);
        module = new OutboundModule(store, properties, voiceCalls, voiceStore,
                workerProvider, dialingProvider, registryProvider, environment);

        Call call = new Call();
        call.setId(1L);
        call.setUserId(1L);
        call.setVoiceCallId("voice-1");
        call.setStage("UNKNOWN");
        call.setOriginateState("SETTLED");
        call.setConnectResult("CONNECTED");
        VoiceCall voice = mock(VoiceCall.class);
        when(voice.terminal()).thenReturn(true);
        when(voiceStore.get("voice-1")).thenReturn(voice);
        when(voiceStore.dialogueResult("voice-1")).thenReturn("COMPLETED");
        when(dialingProvider.getIfAvailable()).thenReturn(dialer);
        when(dialer.query("voice-1")).thenReturn(DialingProvider.RemoteCallState.ABSENT);
        when(store.getOwnedCall(1L, 1L)).thenReturn(call);
        when(store.getCall(1L)).thenReturn(call);
        mockLockedCall(call);

        var result = module.reconcileCall(1L, 1L);

        assertEquals("FINISHED", call.getStage());
        assertEquals("COMPLETED", call.getDialogueResult());
        assertEquals(true, result.get("reconciled"));
    }

    // ── Task list includes statusReason ──

    @Test
    void listTasks_includesStatusReason() {
        Task task = new Task();
        task.setId(1L);
        task.setName("test");
        task.setStatus("STOPPED");
        task.setStatusReason("USER_STOPPED");
        when(store.listTasks(1L)).thenReturn(List.of(task));
        when(store.stageCounts(1L)).thenReturn(java.util.Map.of());
        when(store.totalCalls(1L)).thenReturn(0);

        var result = module.listTasks(1L);

        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) result.get("items");
        assertEquals(1, items.size());
        assertEquals("USER_STOPPED", items.get(0).get("statusReason"));
    }

    @Test
    void recoveryPausesTaskAndPreservesUnsubmittedQueue() {
        Task task = new Task();
        task.setId(8L);
        task.setStatus("RUNNING");
        when(store.listAllRunningTasks()).thenReturn(List.of(task));
        when(store.listActiveCalls(8L)).thenReturn(List.of());
        when(store.lockedTask(eq(8L), any())).thenAnswer(inv -> {
            var fn = inv.getArgument(1, Function.class);
            return fn.apply(task);
        });

        module.recoverAfterRestart();

        assertEquals("PAUSED", task.getStatus());
        assertEquals("RECOVERY_SUSPENDED", task.getStatusReason());
        verify(store, never()).cancelQueuedCalls(8L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void advance_doesNotFinishWhenOnlyLocalVoiceIsTerminal() {
        VoiceCallModule voiceCalls = mock(VoiceCallModule.class);
        VoiceStore voiceStore = mock(VoiceStore.class);
        ObjectProvider<PhoneWorkerClient> workerProvider = mock(ObjectProvider.class);
        ObjectProvider<DialingProvider> dialingProvider = mock(ObjectProvider.class);
        ObjectProvider<com.h.backend.chat.domain.agent.AgentRegistry> registryProvider = mock(ObjectProvider.class);
        DialingProvider dialer = mock(DialingProvider.class);
        Environment environment = mock(Environment.class);
        module = new OutboundModule(store, properties, voiceCalls, voiceStore,
                workerProvider, dialingProvider, registryProvider, environment);

        Call call = new Call();
        call.setId(1L);
        call.setVoiceCallId("voice-1");
        call.setStage("ENDING");
        call.setOriginateState("SETTLED");
        VoiceCall voice = mock(VoiceCall.class);
        when(voice.terminal()).thenReturn(true);
        when(voiceStore.get("voice-1")).thenReturn(voice);
        when(dialingProvider.getIfAvailable()).thenReturn(dialer);
        when(dialer.query("voice-1")).thenReturn(DialingProvider.RemoteCallState.PRESENT);
        when(store.findCalls(anyString(), anyInt())).thenAnswer(inv ->
                "ENDING".equals(inv.getArgument(0, String.class)) ? List.of(call) : List.of());
        when(store.findStaleCalls(anyString(), anyLong())).thenReturn(List.of());
        when(store.listAllRunningTasks()).thenReturn(List.of());
        mockLockedCall(call);

        module.advance();

        assertEquals("ENDING", call.getStage());
        verify(dialer).query("voice-1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void advance_doesNotFinishUnknownWhileOriginateMayStillBeInFlight() {
        VoiceCallModule voiceCalls = mock(VoiceCallModule.class);
        VoiceStore voiceStore = mock(VoiceStore.class);
        ObjectProvider<PhoneWorkerClient> workerProvider = mock(ObjectProvider.class);
        ObjectProvider<DialingProvider> dialingProvider = mock(ObjectProvider.class);
        ObjectProvider<com.h.backend.chat.domain.agent.AgentRegistry> registryProvider = mock(ObjectProvider.class);
        DialingProvider dialer = mock(DialingProvider.class);
        Environment environment = mock(Environment.class);
        module = new OutboundModule(store, properties, voiceCalls, voiceStore,
                workerProvider, dialingProvider, registryProvider, environment);

        Call call = new Call();
        call.setId(2L);
        call.setVoiceCallId("voice-2");
        call.setStage("UNKNOWN");
        call.setOriginateState("PENDING");
        VoiceCall voice = mock(VoiceCall.class);
        when(voice.terminal()).thenReturn(true);
        when(voiceStore.get("voice-2")).thenReturn(voice);
        when(dialingProvider.getIfAvailable()).thenReturn(dialer);
        when(dialer.query("voice-2")).thenReturn(DialingProvider.RemoteCallState.ABSENT);
        when(store.getOwnedCall(1L, 2L)).thenReturn(call);
        when(store.getCall(2L)).thenReturn(call);
        mockLockedCall(call);

        var result = module.reconcileCall(1L, 2L);

        assertEquals("UNKNOWN", call.getStage());
        assertEquals(false, result.get("reconciled"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void knownOriginateFailureEndsLocallyAndPausesWithoutBecomingUnknown() {
        VoiceCallModule voiceCalls = mock(VoiceCallModule.class);
        VoiceStore voiceStore = mock(VoiceStore.class);
        ObjectProvider<PhoneWorkerClient> workerProvider = mock(ObjectProvider.class);
        ObjectProvider<DialingProvider> dialingProvider = mock(ObjectProvider.class);
        ObjectProvider<com.h.backend.chat.domain.agent.AgentRegistry> registryProvider = mock(ObjectProvider.class);
        PhoneWorkerClient worker = mock(PhoneWorkerClient.class);
        DialingProvider dialer = mock(DialingProvider.class);
        Environment environment = mock(Environment.class);
        when(environment.getProperty(anyString(), anyString()))
                .thenAnswer(inv -> inv.getArgument(1, String.class));
        module = new OutboundModule(store, properties, voiceCalls, voiceStore,
                workerProvider, dialingProvider, registryProvider, environment);

        Call call = new Call();
        call.setId(1L);
        call.setTaskId(8L);
        call.setUserId(1L);
        call.setPhoneSnapshot("1000");
        call.setStage("QUEUED");
        Task task = new Task();
        task.setId(8L);
        task.setStatus("RUNNING");
        task.setCommunicationGoal("确认预约");
        task.setAgentBindingSnapshot("{\"agentId\":\"" + ChatAgentIds.HARNESS + "\"}");
        VoiceCall voice = new VoiceCall();
        voice.setId("voice-1");
        voice.setClaimSecret("secret");
        voice.setSessionId("session-1");

        when(workerProvider.getIfAvailable()).thenReturn(worker);
        when(dialingProvider.getIfAvailable()).thenReturn(dialer);
        when(store.hasAnyActiveCall()).thenReturn(false);
        when(store.claimNextRunnableCall()).thenAnswer(inv -> {
            call.setStage("PREPARING");
            call.setOriginateState("NOT_SUBMITTED");
            return call;
        });
        when(store.getTaskForCall(1L)).thenReturn(task);
        when(voiceCalls.createPhoneCall(eq(1L), eq(ChatAgentIds.HARNESS), isNull(), anyString(), eq("确认预约")))
                .thenReturn(Map.of("callId", "voice-1"));
        when(voiceStore.get("voice-1")).thenReturn(voice);
        when(store.commitDialing(1L)).thenAnswer(inv -> {
            call.setStage("DIALING");
            call.setOriginateState("PENDING");
            return true;
        });
        when(dialer.originate(eq("voice-1"), eq("1000"), anyString(), anyString()))
                .thenReturn(new DialingProvider.DialResult(
                        DialingProvider.DialStatus.FAILED, "known provider rejection"));
        when(store.getCall(1L)).thenReturn(call);
        when(store.findCalls(anyString(), anyInt())).thenReturn(List.of());
        when(store.findStaleCalls(anyString(), anyLong())).thenReturn(List.of());
        when(store.listAllRunningTasks()).thenReturn(List.of());
        when(store.transaction(any())).thenAnswer(inv -> inv.getArgument(0, Supplier.class).get());
        when(store.lockedCall(eq(1L), any())).thenAnswer(inv ->
                inv.getArgument(1, Function.class).apply(call));
        when(store.lockedTask(eq(8L), any())).thenAnswer(inv ->
                inv.getArgument(1, Function.class).apply(task));

        module.advance();

        assertEquals("ENDING", call.getStage());
        assertEquals("FAILED", call.getConnectResult());
        assertEquals("SETTLED", call.getOriginateState());
        assertTrue(call.isRemoteEnded());
        assertEquals("PAUSED", task.getStatus());
        verify(voiceCalls).end("voice-1", "DIAL_FAILED");
    }
}
