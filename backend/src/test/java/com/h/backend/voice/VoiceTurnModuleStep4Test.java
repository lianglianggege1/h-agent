package com.h.backend.voice;

import com.h.backend.chat.application.*;
import com.h.backend.chat.interfaces.dto.ChatSessionMessagesPageDto;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRuntimeType;
import com.h.backend.chat.interfaces.dto.ChatSessionMetaDto;
import com.h.backend.chat.interfaces.dto.ChatSessionOpenDto;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.voice.application.VoiceCallModule;
import com.h.backend.voice.application.VoiceReply;
import com.h.backend.voice.application.VoiceReplyContext;
import com.h.backend.voice.application.VoiceTurnModule;
import com.h.backend.voice.domain.VoiceCall;
import com.h.backend.voice.domain.VoiceTurn;
import com.h.backend.voice.infrastructure.HarnessVoiceReply;
import com.h.backend.voice.infrastructure.HAssistantVoiceReply;
import com.h.backend.voice.infrastructure.VoiceProperties;
import com.h.backend.voice.infrastructure.VoiceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class VoiceTurnModuleStep4Test {

    private static final String OPENING_ID = UUID.nameUUIDFromBytes(
            "opening:call-1".getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();

    private VoiceStore store;
    private ChatSessionService sessions;
    private AgentRunService runs;
    private ChatMemorySnapshotService memory;
    private VoiceReply model;
    private VoiceProperties config;
    private ObjectProvider<VoiceCallModule> calls;
    private ObjectProvider<HarnessVoiceReply> harnessReplyProvider;
    private ObjectProvider<AgentRegistry> agentRegistryProvider;
    private ObjectProvider<HAssistantVoiceReply> hAssistantReplyProvider;
    private VoiceTurnModule module;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        store = mock(VoiceStore.class);
        sessions = mock(ChatSessionService.class);
        runs = mock(AgentRunService.class);
        memory = mock(ChatMemorySnapshotService.class);
        model = mock(VoiceReply.class);
        config = new VoiceProperties();
        calls = mock(ObjectProvider.class);
        harnessReplyProvider = mock(ObjectProvider.class);
        agentRegistryProvider = mock(ObjectProvider.class);
        hAssistantReplyProvider = mock(ObjectProvider.class);

        module = new VoiceTurnModule(store, sessions, runs, memory, model, config, calls,
                harnessReplyProvider, agentRegistryProvider, hAssistantReplyProvider);

        when(model.supports(anyString())).thenReturn(true);
        when(model.modelName()).thenReturn("test-model");
        when(model.prepare(anyString(), anyList(), any(), any())).thenReturn(mock(VoiceReply.Execution.class));
        when(sessions.getSessionMessages(anyLong(), anyString(), anyInt(), any()))
                .thenReturn(new ChatSessionMessagesPageDto("sess-1", List.of(), false, null));
    }

    private VoiceCall activePhoneCall(String agentId) {
        VoiceCall c = new VoiceCall();
        c.setChannel("PHONE");
        c.setId("call-1");
        c.setUserId(1L);
        c.setSessionId("sess-1");
        c.setPromptId(1L);
        c.setAgentId(agentId);
        c.setState("ACTIVE");
        c.setWorkerId("worker-1");
        c.setWorkerEpoch(1);
        c.setLeaseUntil(System.currentTimeMillis() + 30000);
        c.setSystemPrompt("system");
        c.setModelName("test-model");
        c.setClaimSecret("secret");
        c.setCreatedAt(System.currentTimeMillis());
        c.setUpdatedAt(System.currentTimeMillis());
        return c;
    }

    @SuppressWarnings("unchecked")
    private void mockLocked(VoiceCall call) {
        when(store.locked(eq("call-1"), any())).thenAnswer(inv -> {
            var fn = inv.getArgument(1, Function.class);
            return fn.apply(call);
        });
    }
    private VoiceTurn openingTurnView() {
        VoiceTurn t = new VoiceTurn();
        t.setId(OPENING_ID);
        t.setCallId("call-1");
        t.setTurnType("OPENING");
        t.setGenerationState("ACCEPTED");
        t.setRunId(42L);
        return t;
    }
    private void mockTurnCreated() {
        when(store.turn("call-1", OPENING_ID)).thenReturn(null, openingTurnView());
    }

    @Test
    void browserVoiceUsesHAssistantAndKeepsTheActualSessionIdentity() {
        var call = activePhoneCall("standard-chat");
        call.setChannel("BROWSER");
        when(store.get("call-1")).thenReturn(call);
        mockLocked(call);
        String turnId = UUID.randomUUID().toString();
        var saved = new AtomicReference<VoiceTurn>();
        when(store.turn("call-1", turnId)).thenAnswer(inv -> saved.get());
        doAnswer(inv -> { saved.set(inv.getArgument(0)); return null; }).when(store).insert(any(VoiceTurn.class));
        when(sessions.appendUserMessage(1L, "sess-1", "接着聊", List.of())).thenReturn(9L);
        when(runs.createRun("sess-1", 1L, 1L, 9L, "standard-chat", null))
                .thenReturn(new AgentRunService.AgentRunHandle(42L));
        var assistant = mock(HAssistantVoiceReply.class);
        var execution = mock(VoiceReply.Execution.class);
        when(hAssistantReplyProvider.getIfAvailable()).thenReturn(assistant);
        when(assistant.prepare(any(VoiceReplyContext.class), any(), any())).thenReturn(execution);

        module.submit("call-1", 1, turnId, "接着聊");

        verify(assistant).prepare(argThat((VoiceReplyContext ctx) ->
                ctx.sessionId().equals("sess-1") && ctx.runId().equals(42L)
                        && ctx.userMessageId().equals(9L) && ctx.agentId().equals("standard-chat")), any(), any());
        verify(execution).start();
        verify(model, never()).prepare(any(VoiceReplyContext.class), any(), any());
        assertNotNull(saved.get().getMemoryCheckpoint());
    }

    @Test
    void browserSettlementPreservesToolContextAndOnlyPlayedTextAcrossRetries() {
        var call = activePhoneCall("standard-chat");
        call.setChannel("BROWSER");
        call.setContextDirty(true);
        mockLocked(call);
        var user = dev.langchain4j.data.message.UserMessage.from("查订单");
        var tool = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("lookup-1").name("lookup").arguments("{}").build();
        var checkpoint = List.<dev.langchain4j.data.message.ChatMessage>of(user,
                dev.langchain4j.data.message.AiMessage.from(tool),
                dev.langchain4j.data.message.ToolExecutionResultMessage.from(tool, "已发货"));
        var turn = new VoiceTurn();
        turn.setState("COMMITTED");
        turn.setMemoryCheckpoint(dev.langchain4j.data.message.ChatMessageSerializer.messagesToJson(checkpoint));
        turn.setGeneratedText("订单已发货，明天到达");
        turn.setPlayedChars(5);
        turn.setPlayoutState("INTERRUPTED");
        when(store.latestTurn("call-1")).thenReturn(turn);

        module.syncContext("call-1");
        // Simulate the DB dirty-bit transaction failing after the memory cache write.
        call.setContextDirty(true);
        module.syncContext("call-1");

        var expected = new java.util.ArrayList<>(checkpoint);
        expected.add(dev.langchain4j.data.message.AiMessage.from("订单已发货\n（语音回复已中断，以上为播放进度估计）"));
        verify(memory, times(2)).cacheMemory(any(), eq(expected));
        verify(sessions, never()).getSessionMessages(any(), any(), anyInt(), any());
    }

    @Test
    void contextRepairCannotReplaceMemoryWhileAgentIsStillRunning() {
        var call = activePhoneCall("standard-chat");
        call.setChannel("BROWSER");
        call.setContextDirty(true);
        mockLocked(call);
        when(store.openTurn("call-1")).thenReturn(new VoiceTurn());

        module.syncContext("call-1");

        verifyNoInteractions(memory);
        assertTrue(call.isContextDirty());
    }

    // ── 开场轮次使用固定幂等身份 ──

    @Test
    void submitOpening_createsOpeningTurnWithFixedId() {
        var call = activePhoneCall("standard-chat");
        when(store.get("call-1")).thenReturn(call);
        mockTurnCreated();
        when(store.openTurn("call-1")).thenReturn(null);
        mockLocked(call);
        when(runs.createRun(any(), anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(new AgentRunService.AgentRunHandle(42L));

        var result = module.submitOpening("call-1", 1);

        assertEquals(OPENING_ID, result.get("turnId"));
        verify(store).insert(argThat((VoiceTurn t) -> "OPENING".equals(t.getTurnType()) && t.getUserText() == null));
    }

    @Test
    void submitOpening_isIdempotent() {
        var call = activePhoneCall("standard-chat");
        VoiceTurn existing = new VoiceTurn();
        existing.setId(OPENING_ID);
        existing.setCallId("call-1");
        existing.setTurnType("OPENING");
        existing.setGenerationState("GENERATING");
        existing.setRunId(42L);

        when(store.get("call-1")).thenReturn(call);
        when(store.turn("call-1", OPENING_ID)).thenReturn(existing);
        mockLocked(call);

        var result = module.submitOpening("call-1", 1);

        assertEquals(OPENING_ID, result.get("turnId"));
        verify(store, never()).insert(any(VoiceTurn.class));
        verify(store, never()).insert(any(com.h.backend.voice.domain.VoiceCall.class));
        verify(runs, never()).createRun(anyString(), anyLong(), anyLong(), any(), anyString(), any());
    }

    // ── PHONE + harness 不回退到 AnthropicVoiceReply ──

    @Test
    void selectReply_harnessAgent_doesNotFallbackToAnthropic() {
        var call = activePhoneCall(ChatAgentIds.HARNESS);
        when(store.get("call-1")).thenReturn(call);
        mockTurnCreated();
        when(store.openTurn("call-1")).thenReturn(null);
        mockLocked(call);
        when(runs.createRun(any(), anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(new AgentRunService.AgentRunHandle(42L));
        when(harnessReplyProvider.getIfAvailable()).thenReturn(null);
        var agentDef = new AgentDefinition(ChatAgentIds.HARNESS, "Harness", "Chat",
                List.of(), "", new Object(), AgentRuntimeType.HARNESS_STREAMING, true);
        var registry = mock(AgentRegistry.class);
        when(registry.requireEnabled(ChatAgentIds.HARNESS)).thenReturn(agentDef);
        when(agentRegistryProvider.getIfAvailable()).thenReturn(registry);

        module.submitOpening("call-1", 1);

        // Must NOT fall back to AnthropicVoiceReply
        verify(model, never()).prepare(any(VoiceReplyContext.class), any(), any());
        verify(model, never()).prepare(anyString(), anyList(), any(), any());
    }

    @Test
    void selectReply_standardChat_usesAnthropicVoiceReply() {
        var call = activePhoneCall("standard-chat");
        when(store.get("call-1")).thenReturn(call);
        mockTurnCreated();
        when(store.openTurn("call-1")).thenReturn(null);
        mockLocked(call);
        when(runs.createRun(any(), anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(new AgentRunService.AgentRunHandle(42L));

        module.submitOpening("call-1", 1);

        verify(model).prepare(any(VoiceReplyContext.class), any(), any());
    }

    // ── HarnessVoiceReply 不自动提交消息 ──

    @Test
    void harnessReply_doesNotAutoCommitMessages() {
        var harnessReply = mock(HarnessVoiceReply.class);
        when(harnessReply.supports(ChatAgentIds.HARNESS)).thenReturn(true);
        when(harnessReply.modelName()).thenReturn("harness-model");
        when(harnessReply.prepare(any(VoiceReplyContext.class), any(), any()))
                .thenReturn(mock(VoiceReply.Execution.class));

        var call = activePhoneCall(ChatAgentIds.HARNESS);
        when(store.get("call-1")).thenReturn(call);
        mockTurnCreated();
        when(store.openTurn("call-1")).thenReturn(null);
        mockLocked(call);
        when(runs.createRun(any(), anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(new AgentRunService.AgentRunHandle(42L));
        when(harnessReplyProvider.getIfAvailable()).thenReturn(harnessReply);

        var agentDef = new AgentDefinition(ChatAgentIds.HARNESS, "Harness", "Chat",
                List.of(), "", new Object(), AgentRuntimeType.HARNESS_STREAMING, true);
        var registry = mock(AgentRegistry.class);
        when(registry.requireEnabled(ChatAgentIds.HARNESS)).thenReturn(agentDef);
        when(agentRegistryProvider.getIfAvailable()).thenReturn(registry);

        module.submitOpening("call-1", 1);

        verify(harnessReply).prepare(any(VoiceReplyContext.class), any(), any());
        verify(sessions, never()).appendAssistantMessage(anyLong(), anyString(), anyString());
        verify(sessions, never()).appendAssistantMessage(anyLong(), anyString(), anyString(), anyList());
    }

    // ── 同一 turnId 重试只执行一次 ──

    @Test
    void submit_duplicateTurnId_returnsExistingTurn() {
        var call = activePhoneCall("standard-chat");
        String turnId = UUID.randomUUID().toString();
        VoiceTurn existing = new VoiceTurn();
        existing.setId(turnId);
        existing.setCallId("call-1");
        existing.setUserText("hello");
        existing.setGenerationState("GENERATING");
        existing.setRunId(42L);

        when(store.get("call-1")).thenReturn(call);
        when(store.turn("call-1", turnId)).thenReturn(existing);
        mockLocked(call);

        var result = module.submit("call-1", 1, turnId, "hello");

        assertEquals(turnId, result.get("turnId"));
        verify(store, never()).insert(any(VoiceTurn.class));
        verify(store, never()).insert(any(com.h.backend.voice.domain.VoiceCall.class));
        verify(runs, never()).createRun(anyString(), anyLong(), anyLong(), anyLong(), anyString(), any());
    }

    // ── 开场轮次 context 包含正确的 agentBean ──

    @Test
    void submitOpening_passesAgentBeanForHarness() {
        var harnessReply = mock(HarnessVoiceReply.class);
        when(harnessReply.supports(ChatAgentIds.HARNESS)).thenReturn(true);
        when(harnessReply.modelName()).thenReturn("harness-model");
        var agentBean = new Object();
        var execution = mock(VoiceReply.Execution.class);
        when(harnessReply.prepare(any(VoiceReplyContext.class), any(), any())).thenReturn(execution);

        var call = activePhoneCall(ChatAgentIds.HARNESS);
        when(store.get("call-1")).thenReturn(call);
        mockTurnCreated();
        when(store.openTurn("call-1")).thenReturn(null);
        mockLocked(call);
        when(runs.createRun(any(), anyLong(), anyLong(), any(), any(), any()))
                .thenReturn(new AgentRunService.AgentRunHandle(42L));
        when(harnessReplyProvider.getIfAvailable()).thenReturn(harnessReply);

        var agentDef = new AgentDefinition(ChatAgentIds.HARNESS, "Harness", "Chat",
                List.of(), "", agentBean, AgentRuntimeType.HARNESS_STREAMING, true);
        var registry = mock(AgentRegistry.class);
        when(registry.requireEnabled(ChatAgentIds.HARNESS)).thenReturn(agentDef);
        when(agentRegistryProvider.getIfAvailable()).thenReturn(registry);

        var capturedCtx = new AtomicReference<VoiceReplyContext>();
        when(harnessReply.prepare(any(VoiceReplyContext.class), any(), any())).thenAnswer(inv -> {
            capturedCtx.set(inv.getArgument(0, VoiceReplyContext.class));
            return execution;
        });

        module.submitOpening("call-1", 1);

        var ctx = capturedCtx.get();
        assertNotNull(ctx);
        assertEquals(ChatAgentIds.HARNESS, ctx.agentId());
        assertSame(agentBean, ctx.agentBean());
        assertEquals("开始对话", ctx.userMessage());
        assertNull(ctx.userMessageId());
    }
}
