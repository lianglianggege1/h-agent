package com.h.backend.voice;

import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.ChatMemorySnapshotService;
import com.h.backend.chat.domain.memory.ChatMemoryContext;
import com.h.backend.chat.infrastructure.ai.HAssistant;
import com.h.backend.chat.infrastructure.memory.RedisChatMemoryStore;
import com.h.backend.memory.domain.MemoryInvocationContext;
import com.h.backend.skill.application.SkillRuntimeService;
import com.h.backend.voice.application.VoiceReplyContext;
import com.h.backend.voice.infrastructure.HAssistantVoiceReply;
import dev.langchain4j.data.message.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class StandardVoiceAgentTest {
    static class OrderTool {
        @dev.langchain4j.agent.tool.Tool("Read the order status")
        public String orderStatus() { return "已发货"; }
    }

    @Test
    void realAiServiceContinuesFromTextThroughVoiceToolsAndBackToText() throws Exception {
        var snapshots = mock(ChatMemorySnapshotService.class);
        var durable = new AtomicReference<List<ChatMessage>>(List.of());
        when(snapshots.loadSnapshot(any())).thenAnswer(inv -> Optional.of(durable.get()));
        doAnswer(inv -> { durable.set(List.copyOf(inv.getArgument(1))); return null; })
                .when(snapshots).cacheMemory(any(), any());
        var store = new RedisChatMemoryStore(snapshots);
        var requests = new java.util.concurrent.CopyOnWriteArrayList<List<ChatMessage>>();
        var orderTool = spy(new OrderTool());
        var model = new dev.langchain4j.model.chat.StreamingChatModel() {
            @Override public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,
                                         dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                requests.add(request.messages());
                AiMessage answer = switch (requests.size()) {
                    case 1 -> AiMessage.from("记住了订单号42");
                    case 2 -> AiMessage.from(ToolExecutionRequest.builder().id("order-42")
                            .name("orderStatus").arguments("{}").build());
                    case 3 -> AiMessage.from("订单已发货，明天到达");
                    default -> AiMessage.from("继续文字交流");
                };
                if (answer.text() != null) handler.onPartialResponse(answer.text());
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(answer).build());
            }
        };
        HAssistant assistant = dev.langchain4j.service.AiServices.builder(HAssistant.class)
                .streamingChatModel(model).tools(orderTool)
                .systemMessage("原有角色与 Skill 指令")
                .chatRequestTransformer(store::withGenerationInstructions)
                .chatMemoryProvider(id -> dev.langchain4j.memory.chat.MessageWindowChatMemory.builder()
                        .id(id).maxMessages(1000).chatMemoryStore(store).build()).build();
        var parameters = new MemoryInvocationContext(7L, "standard-chat", "same-session", 41L,
                "same-session", 3L).toInvocationParameters();
        var first = new CompletableFuture<ChatResponse>();
        assistant.streamChat("7:3:same-session", "我的订单号42", parameters)
                .onCompleteResponse(first::complete).onError(first::completeExceptionally).start();
        first.get(3, TimeUnit.SECONDS);
        var beforeVoice = durable.get();
        var runs = mock(AgentRunService.class);
        var reply = new HAssistantVoiceReply(assistant, store, mock(SkillRuntimeService.class), runs);
        var terminal = new CompletableFuture<String>();
        var spoken = new StringBuilder();
        var execution = reply.prepare(new VoiceReplyContext(7L, "same-session", 3L, "standard-chat", null,
                "", List.of(), "查一下刚才的订单", 42L, 9L, "test"), spoken::append, terminal::complete);
        execution.start();
        assertEquals("GENERATED", terminal.get(3, TimeUnit.SECONDS));
        verify(orderTool).orderStatus();
        verify(runs).recordToolUsage(42L, "orderStatus");
        assertEquals(SystemMessage.from("原有角色与 Skill 指令"), requests.getFirst().getFirst());
        var voiceSystem = SystemMessage.from("原有角色与 Skill 指令\n\n" + HAssistantVoiceReply.VOICE_INSTRUCTIONS);
        assertEquals(voiceSystem, requests.get(1).getFirst());
        assertEquals(voiceSystem, requests.get(2).getFirst(), "Tool follow-ups retain the voice instructions exactly once");
        assertEquals(SystemMessage.from("原有角色与 Skill 指令"), execution.checkpoint().getFirst(),
                "Voice instructions must not enter the persisted checkpoint");
        assertTrue(requests.get(1).contains(UserMessage.from("我的订单号42")));
        assertEquals(beforeVoice, durable.get(), "Unplayed voice output must not change durable memory");
        assertEquals("订单已发货，明天到达", spoken.toString());
        // The turn module commits the played prefix using this checkpoint.
        var delivered = new java.util.ArrayList<>(execution.checkpoint());
        delivered.add(AiMessage.from("订单已发货"));
        snapshots.cacheMemory(new ChatMemoryContext(7L, 3L, "same-session"), delivered);
        var finalText = new CompletableFuture<ChatResponse>();
        assistant.streamChat("7:3:same-session", "继续", parameters)
                .onCompleteResponse(finalText::complete).onError(finalText::completeExceptionally).start();
        finalText.get(3, TimeUnit.SECONDS);
        assertEquals(SystemMessage.from("原有角色与 Skill 指令"), requests.getLast().getFirst(),
                "Returning to text chat must restore the original system instructions");
        assertTrue(requests.getLast().stream().anyMatch(ToolExecutionResultMessage.class::isInstance));
        assertTrue(requests.getLast().contains(AiMessage.from("订单已发货")));
        assertFalse(requests.getLast().contains(AiMessage.from("订单已发货，明天到达")));
    }

    @Test
    void voiceContinuesThroughTheTextAssistantWithoutPersistingUnplayedAnswer() throws Exception {
        var assistant = mock(HAssistant.class);
        var snapshots = mock(ChatMemorySnapshotService.class);
        var store = new RedisChatMemoryStore(snapshots);
        var skills = mock(SkillRuntimeService.class);
        var runs = mock(AgentRunService.class);
        var stream = mock(TokenStream.class, RETURNS_SELF);
        var past = List.<ChatMessage>of(UserMessage.from("之前的问题"), AiMessage.from("之前的答复"));
        when(snapshots.loadSnapshot(any())).thenReturn(Optional.of(past));
        when(assistant.streamChat(anyString(), anyString(), any())).thenReturn(stream);
        var completion = new AtomicReference<Consumer<ChatResponse>>();
        doAnswer(inv -> { completion.set(inv.getArgument(0)); return stream; }).when(stream).onCompleteResponse(any());
        doAnswer(inv -> {
            assertEquals(past, store.getMessages("7:3:same-session"));
            store.updateMessages("7:3:same-session", List.of(past.get(0), past.get(1),
                    UserMessage.from("继续刚才的话题"), AiMessage.from("完整但尚未播放的回复")));
            completion.get().accept(ChatResponse.builder().aiMessage(AiMessage.from("完整但尚未播放的回复")).build());
            return null;
        }).when(stream).start();
        var reply = new HAssistantVoiceReply(assistant, store, skills, runs);
        var terminal = new CompletableFuture<String>();
        var ctx = new VoiceReplyContext(7L, "same-session", 3L, "standard-chat", null,
                "prompt", List.of(UserMessage.from("继续刚才的话题")), "继续刚才的话题", 42L, 9L, "test");
        var execution = reply.prepare(ctx, ignored -> {}, terminal::complete);
        execution.start();
        assertEquals("GENERATED", terminal.get(3, TimeUnit.SECONDS));
        var parameters = ArgumentCaptor.forClass(InvocationParameters.class);
        verify(assistant).streamChat(eq("7:3:same-session"), eq("继续刚才的话题"), parameters.capture());
        assertEquals(new MemoryInvocationContext(7L, "standard-chat", "same-session", 42L, "same-session", 3L),
                MemoryInvocationContext.from(parameters.getValue()));
        verify(skills).snapshotForTopLevelRun(7L, 42L, "7:3:same-session");
        assertEquals(List.of(past.get(0), past.get(1), UserMessage.from("继续刚才的话题")), execution.checkpoint());
        verify(snapshots, never()).cacheMemory(any(), any());
        // Closing the deferred generation restores normal text-chat access to the durable snapshot.
        assertEquals(past, store.getMessages("7:3:same-session"));
    }

    @Test
    void deferredMemoryPreservesCompletedToolsButDropsUnplayedAnswerAndUnmatchedToolRequests() {
        var snapshots = mock(ChatMemorySnapshotService.class);
        when(snapshots.loadSnapshot(any())).thenReturn(Optional.empty());
        var store = new RedisChatMemoryStore(snapshots);
        var user = UserMessage.from("查一下订单");
        var request = ToolExecutionRequest.builder().id("tool-1").name("lookup").arguments("{}").build();
        var toolCall = AiMessage.from(request);
        var result = ToolExecutionResultMessage.from(request, "已发货");
        try (var pending = store.defer("7:3:same-session", List.of())) {
            store.updateMessages("7:3:same-session", List.of(user, toolCall));
            assertEquals(List.of(user), pending.checkpoint());
            store.updateMessages("7:3:same-session", List.of(user, toolCall, result, AiMessage.from("订单已发货")));
            assertEquals(List.of(user, toolCall, result), pending.checkpoint());
            verify(snapshots, never()).cacheMemory(any(), any());
        }
    }

    @Test
    void cancellationWaitsForActualTerminationBeforeReleasingMemory() throws Exception {
        var snapshots = mock(ChatMemorySnapshotService.class);
        when(snapshots.loadSnapshot(any())).thenReturn(Optional.empty());
        var store = new RedisChatMemoryStore(snapshots);
        var assistant = mock(HAssistant.class);
        var stream = mock(TokenStream.class, RETURNS_SELF);
        when(assistant.streamChat(anyString(), anyString(), any())).thenReturn(stream);
        var complete = new CompletableFuture<Consumer<ChatResponse>>();
        doAnswer(inv -> { complete.complete(inv.getArgument(0)); return stream; }).when(stream).onCompleteResponse(any());
        var reply = new HAssistantVoiceReply(assistant, store, mock(SkillRuntimeService.class), mock(AgentRunService.class));
        var terminal = new CompletableFuture<String>();
        var execution = reply.prepare(new VoiceReplyContext(7L, "same-session", 3L, "standard-chat", null,
                "", List.of(), "继续", 42L, 9L, "test"), ignored -> fail("cancelled output"), terminal::complete);
        execution.start();
        var callback = complete.get(3, TimeUnit.SECONDS);
        execution.cancel();
        assertFalse(terminal.isDone());
        assertThrows(IllegalStateException.class, () -> store.defer("7:3:same-session", List.of()));
        callback.accept(ChatResponse.builder().aiMessage(AiMessage.from("late reply")).build());
        assertEquals("CANCELLED", terminal.get(3, TimeUnit.SECONDS));
        try (var ignored = store.defer("7:3:same-session", List.of())) { }
    }
}
