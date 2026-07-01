package com.h.backend.chat.domain.agent;

import com.h.backend.chat.interfaces.dto.AgentStepPayloadDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.AgentRunTelemetryService;
import com.h.backend.chat.application.ChatSessionService;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

@Slf4j
@Component
public class AgenticSyncExecutor implements ChatAgentExecutor {

    private static final String AGENT_CHAT_METHOD = "chat";

    private final ChatSessionService chatSessionService;
    private final AgentRunService agentRunService;
    private final AgentRunTelemetryService agentRunTelemetryService;
    private final AgentStepEventBridge agentStepEventBridge;

    public AgenticSyncExecutor(
            ChatSessionService chatSessionService,
            AgentRunService agentRunService,
            AgentRunTelemetryService agentRunTelemetryService,
            AgentStepEventBridge agentStepEventBridge
    ) {
        this.chatSessionService = chatSessionService;
        this.agentRunService = agentRunService;
        this.agentRunTelemetryService = agentRunTelemetryService;
        this.agentStepEventBridge = agentStepEventBridge;
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
            Long assistantMessageId = chatSessionService.appendAssistantMessage(
                    command.userId(),
                    command.sessionId(),
                    reply
            );
            ChatSessionMessageDto assistantMessage = chatSessionService.getOwnedMessage(
                    command.userId(),
                    command.sessionId(),
                    assistantMessageId
            );
            agentRunService.completeRun(command.runHandle().id(), assistantMessageId);
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
        Method chatMethod = findAgentChatMethod(agentBean);
        try {
            Object result = chatMethod.invoke(agentBean, command.memoryId(), command.userMessage());
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

    private Method findAgentChatMethod(Object agentBean) {
        if (agentBean == null) {
            throw new IllegalStateException("Unsupported AGENTIC_SYNC agent bean: null");
        }
        try {
            Method method = agentBean.getClass().getMethod(AGENT_CHAT_METHOD, String.class, String.class);
            if (!ResultWithAgenticScope.class.isAssignableFrom(method.getReturnType())) {
                throw new IllegalStateException("AGENTIC_SYNC agent chat method must return ResultWithAgenticScope: "
                        + agentBean.getClass().getName());
            }
            return method;
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Unsupported AGENTIC_SYNC agent bean: "
                    + agentBean.getClass().getName()
                    + ". Expected method chat(String memoryId, String message)", ex);
        }
    }

    private static Integer stateLength(Object state) {
        return state == null ? null : String.valueOf(state).length();
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
