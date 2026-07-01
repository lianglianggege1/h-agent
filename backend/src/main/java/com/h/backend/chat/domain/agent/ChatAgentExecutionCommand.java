package com.h.backend.chat.domain.agent;

import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceUseDto;
import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.AgentRunTelemetryService;
import reactor.core.publisher.FluxSink;

import java.util.List;

public record ChatAgentExecutionCommand(
        FluxSink<ChatStreamEvent> sink,
        Long userId,
        Long resolvedPromptId,
        String sessionId,
        String userMessage,
        List<ChatMessageResourceUseDto> resources,
        String memoryId,
        AgentDefinition agent,
        AgentRunService.AgentRunHandle runHandle,
        AgentRunTelemetryService.TelemetryRun telemetryRun,
        Runnable onTerminal
) {
}
