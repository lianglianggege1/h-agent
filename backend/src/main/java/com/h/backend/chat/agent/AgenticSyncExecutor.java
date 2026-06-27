package com.h.backend.chat.agent;

import com.h.backend.chat.ai.carrentalassistant.services.CarRentalAssistant;
import com.h.backend.chat.ai.carrentalassistant.services.ExportAssistant;
import com.h.backend.chat.dto.AgentStepPayloadDto;
import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.service.AgentRunService;
import com.h.backend.chat.service.AgentRunTelemetryService;
import com.h.backend.chat.service.ChatSessionService;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.FluxSink;

@Slf4j
@Component
public class AgenticSyncExecutor implements ChatAgentExecutor {

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
            agentRunService.completeRun(command.runHandle().id(), assistantMessageId);
            agentRunTelemetryService.markSuccess(command.telemetryRun());
            emitAndCompleteIfActive(command.sink(), new ChatStreamEvent("done", ""));
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
        if (agentBean instanceof CarRentalAssistant assistant) {
            return assistant.chat(command.memoryId(), command.userMessage());
        } else if (agentBean instanceof ExportAssistant assistant) {
            return assistant.chat(command.memoryId(), command.userMessage());
        }
        throw new IllegalStateException("Unsupported AGENTIC_SYNC agent bean: "
                + (agentBean == null ? "null" : agentBean.getClass().getName()));
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
