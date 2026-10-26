package com.h.backend.order;

import com.h.agent.observability.NoopAgentObservability;
import com.h.backend.chat.application.*;
import com.h.backend.chat.application.impl.ChatServiceImpl;
import com.h.backend.chat.domain.agent.*;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.chat.interfaces.dto.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class OrderContinuationTest {
    @Test void trustedRecoveryReusesTheOriginalProductMessage() {
        var sessions=mock(ChatSessionService.class);var runs=mock(AgentRunService.class);
        var collaboration=mock(HarnessCollaborationService.class);var executor=mock(ChatAgentExecutor.class);
        when(executor.runtimeType()).thenReturn(AgentRuntimeType.HARNESS_STREAMING);
        when(collaboration.resolveExecutionSession(1L,"chat")).thenReturn(new HarnessExecutionSession("chat","chat",null,null,null,null));
        when(sessions.getOwnedMessage(1L,"chat",77L)).thenReturn(new ChatSessionMessageDto("77","user","TEXT","处理订单",null,List.of(),null));
        when(runs.createRun(eq("chat"),eq(1L),isNull(),eq(77L),eq(ChatAgentIds.ORDER_PROCESSING),isNull()))
                .thenReturn(new AgentRunService.AgentRunHandle(88L));
        var permit=mock(ChatStreamConcurrencyGuard.Permit.class);when(permit.acquired()).thenReturn(true);
        var registry=new AgentRegistry(List.of(new AgentDefinition(ChatAgentIds.ORDER_PROCESSING,"订单","订单",List.of(),"",new Object(),AgentRuntimeType.HARNESS_STREAMING,true)));
        doAnswer(call->{ChatAgentExecutionCommand command=call.getArgument(0);command.sink().next(new ChatStreamEvent("done",""));command.sink().complete();return null;}).when(executor).execute(any());
        try(var pool=Executors.newSingleThreadExecutor()){
            var service=new ChatServiceImpl(mock(SystemPromptService.class),sessions,runs,NoopAgentObservability.getInstance(),pool,
                    (s,u)->permit,null,registry,new ChatMemoryIdFactory(),collaboration,List.of(executor));
            var events=service.streamChat(1L,null,ChatAgentIds.ORDER_PROCESSING,"chat","处理订单",List.of())
                    .contextWrite(context->context.put(OrderContinuation.class,new OrderContinuation(77L)))
                    .collectList().block(java.time.Duration.ofSeconds(5));
            assertNotNull(events);assertTrue(events.stream().anyMatch(event->"done".equals(event.type())));
            verify(sessions,never()).appendUserMessage(any(),any(),any(),any());
            verify(executor).execute(argThat(command->Objects.equals(command.userMessageId(),77L)));
        }
    }
}
