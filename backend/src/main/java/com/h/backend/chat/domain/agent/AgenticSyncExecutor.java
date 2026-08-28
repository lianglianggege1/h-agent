package com.h.backend.chat.domain.agent;

import com.h.backend.chat.interfaces.dto.AgentStepPayloadDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.AgentRunTelemetryService;
import com.h.backend.memory.application.SuccessfulTurnCommitter;
import com.h.backend.memory.domain.MemoryInvocationContext;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.invocation.InvocationParameters;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Component
public class AgenticSyncExecutor implements ChatAgentExecutor {

    private final AgentRunService agentRunService;
    private final AgentRunTelemetryService agentRunTelemetryService;
    private final AgentStepEventBridge agentStepEventBridge;
    private final SuccessfulTurnCommitter successfulTurnCommitter;
    private final ConcurrentMap<Class<?>, Method> chatMethods = new ConcurrentHashMap<>();

    public AgenticSyncExecutor(
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            AgentStepEventBridge agentStepEventBridge,
            SuccessfulTurnCommitter successfulTurnCommitter
    ) {
        this.agentRunService = agentRunService;
        this.agentRunTelemetryService = agentRunTelemetryService;
        this.agentStepEventBridge = agentStepEventBridge;
        this.successfulTurnCommitter = successfulTurnCommitter;
    }

    @Override
    public AgentRuntimeType runtimeType() {
        return AgentRuntimeType.AGENTIC_SYNC;
    }

    @Override
    public void execute(ChatAgentExecutionCommand command) {
        agentStepEventBridge.register(command.memoryId(), payload -> emitAgentStep(command, payload));
        try {
            ResultWithAgenticScope<String> result = executeSelectedAgent(command);
            String reply = result == null || result.result() == null ? "" : result.result();
            if (reply.isBlank()) {
                IllegalStateException error = new IllegalStateException("AI 未返回有效内容");
                agentRunService.failRun(command.runHandle().id(), error.getMessage());
                agentRunTelemetryService.markFailure(command.telemetryRun(), error);
                emitAndCompleteIfActive(command.sink(), new ChatStreamEvent("error", "AI 未返回有效内容"));
                return;
            }
            emitIfActive(command.sink(), new ChatStreamEvent("chunk", reply));
            // assistant message、run success 与 memory capture outbox 同一事务提交
            ChatSessionMessageDto assistantMessage = successfulTurnCommitter.commit(command, reply);
            agentRunTelemetryService.markSuccess(command.telemetryRun());
            emitAndCompleteIfActive(command.sink(), new ChatStreamEvent("done", "", assistantMessage));
        } catch (Exception ex) {
            log.error("Error executing agentic chat", ex);
            agentRunService.failRun(command.runHandle().id(), ex.getMessage() == null
                    ? "AI 服务调用失败"
                    : ex.getMessage());
            agentRunTelemetryService.markFailure(command.telemetryRun(), ex);
            emitAndCompleteIfActive(command.sink(), new ChatStreamEvent("error", "AI 服务调用失败"));
        } finally {
            agentStepEventBridge.unregister(command.memoryId());
            command.onTerminal().run();
        }
    }

    private ResultWithAgenticScope<String> executeSelectedAgent(ChatAgentExecutionCommand command) {
        Object agentBean = command.agent().agentBean();
        Method chatMethod = chatMethods.computeIfAbsent(agentBean.getClass(),
                beanClass -> AgenticChatMethodResolver.requireChatMethod(agentBean));
        try {
            InvocationParameters parameters = memoryInvocationContext(command).toInvocationParameters();
            Object result = chatMethod.invoke(agentBean, command.memoryId(), command.userMessage(), parameters);
            if (result == null) {
                return null;
            }
            if (result instanceof ResultWithAgenticScope<?> scopedResult) {
                @SuppressWarnings("unchecked")
                ResultWithAgenticScope<String> typedResult = (ResultWithAgenticScope<String>) scopedResult;
                return typedResult;
            }
            throw new IllegalStateException("AGENTIC_SYNC agent chat method returned unsupported type: "
                    + result.getClass().getName());
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException("Unable to access AGENTIC_SYNC agent chat method: "
                    + agentBean.getClass().getName(), ex);
        } catch (InvocationTargetException ex) {
            Throwable target = ex.getTargetException();
            if (target instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (target instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("AGENTIC_SYNC agent chat method failed", target);
        }
    }

    private MemoryInvocationContext memoryInvocationContext(ChatAgentExecutionCommand command) {
        return new MemoryInvocationContext(
                command.userId(),
                command.agent().agentId(),
                command.rootSessionId(),
                command.runHandle().id(),
                command.sessionId(),
                command.resolvedPromptId()
        );
    }

    private void emitAgentStep(ChatAgentExecutionCommand command, AgentStepPayloadDto payload) {
        AgentStepPayloadDto enriched = new AgentStepPayloadDto(
                String.valueOf(command.runHandle().id()),
                command.agent().agentId(),
                payload.invocationId(),
                payload.nodeId(),
                payload.nodeName(),
                payload.topology(),
                payload.status(),
                payload.depth(),
                payload.sequence()
        );
        emitIfActive(
                command.sink(),
                new ChatStreamEvent("agent_step", "正在执行：" + payload.nodeName(), null, enriched)
        );
    }

    private void emitIfActive(FluxSink<ChatStreamEvent> sink, ChatStreamEvent event) {
        if (sink.isCancelled()) {
            return;
        }
        try {
            sink.next(event);
        } catch (RuntimeException ex) {
            log.debug("Skipping chat stream event after subscriber cancellation", ex);
        }
    }

    private void emitAndCompleteIfActive(FluxSink<ChatStreamEvent> sink, ChatStreamEvent event) {
        if (sink.isCancelled()) {
            return;
        }
        try {
            sink.next(event);
            sink.complete();
        } catch (RuntimeException ex) {
            log.debug("Skipping chat stream completion after subscriber cancellation", ex);
        }
    }
}
