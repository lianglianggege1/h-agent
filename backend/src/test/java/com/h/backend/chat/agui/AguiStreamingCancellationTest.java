package com.h.backend.chat.agui;

import com.h.agent.observability.lifecycle.AgentExecutionObservation;
import com.h.backend.chat.application.*;
import com.h.backend.chat.application.agui.ChatExecutionCancellation;
import com.h.backend.chat.domain.agent.*;
import com.h.backend.chat.infrastructure.ai.HAssistant;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.memory.application.SuccessfulTurnCommitter;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.service.TokenStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AguiStreamingCancellationTest {
    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void explicitStopCancelsThinkingStreamAndRejectsLateCompletion() {
        HAssistant assistant = mock(HAssistant.class);
        TokenStream stream = mock(TokenStream.class, RETURNS_SELF);
        when(assistant.streamChat(anyString(), anyString(), any())).thenReturn(stream);
        AgentRunService runs = mock(AgentRunService.class);
        AgentExecutionObservation observation = mock(AgentExecutionObservation.class);
        SuccessfulTurnCommitter committer = mock(SuccessfulTurnCommitter.class);
        Runnable terminal = mock(Runnable.class);
        var executor = new HAssistantStreamingExecutor(assistant, mock(ChatSessionService.class), runs,
                new ChatStreamEventBridge(), committer);
        var cancellation = new ChatExecutionCancellation();
        var subscription = Flux.<ChatStreamEvent>create(sink -> executor.execute(new ChatAgentExecutionCommand(sink,
                1L, 2L, "session", "hello", List.of(), "memory",
                new AgentDefinition("standard-chat", "助手", "通用", List.of(), "", assistant,
                        AgentRuntimeType.STANDARD_STREAMING_CHAT, true),
                new AgentRunService.AgentRunHandle(1L), observation, terminal)))
                .contextWrite(context -> context.put(ChatExecutionCancellation.CONTEXT_KEY, cancellation)).subscribe();
        ArgumentCaptor<BiConsumer<PartialThinking, PartialThinkingContext>> thinking = ArgumentCaptor.forClass(BiConsumer.class);
        ArgumentCaptor<Consumer<ChatResponse>> complete = ArgumentCaptor.forClass(Consumer.class);
        verify(stream).onPartialThinkingWithContext(thinking.capture());
        verify(stream).onCompleteResponse(complete.capture());
        StreamingHandle handle = mock(StreamingHandle.class);
        thinking.getValue().accept(new PartialThinking("thinking"), new PartialThinkingContext(handle));
        cancellation.cancel();
        cancellation.cancel();
        complete.getValue().accept(null);
        verify(handle).cancel();
        verify(runs, times(1)).failRun(1L, "用户已停止任务");
        verify(terminal, times(1)).run();
        verifyNoInteractions(committer);
        subscription.dispose();
    }
}
