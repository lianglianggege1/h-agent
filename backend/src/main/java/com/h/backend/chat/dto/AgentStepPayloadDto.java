package com.h.backend.chat.dto;

public record AgentStepPayloadDto(
        String runId,
        String agentId,
        String invocationId,
        String nodeId,
        String nodeName,
        String topology,
        String status,
        Integer depth,
        Integer sequence
) {
}
