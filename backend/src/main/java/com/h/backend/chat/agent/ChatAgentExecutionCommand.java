package com.h.backend.chat.agent;

import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.service.AgentRunService;
import com.h.backend.chat.service.AgentRunTelemetryService;
import reactor.core.publisher.FluxSink;

import java.util.List;

public record ChatAgentExecutionCommand(
        FluxSink<ChatStreamEvent> sink,
        Long userId,
        Long resolvedPromptId,
        String sessionId,
        String userMessage,
        List<String> referenceResourceIds,
        String memoryId,
        AgentDefinition agent,
        AgentRunService.AgentRunHandle runHandle,
        AgentRunTelemetryService.TelemetryRun telemetryRun,
        Runnable onTerminal
) {
}
