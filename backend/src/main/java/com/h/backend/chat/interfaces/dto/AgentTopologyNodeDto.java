package com.h.backend.chat.interfaces.dto;

import java.util.List;

public record AgentTopologyNodeDto(
        String nodeId,
        String name,
        String topology,
        String type,
        String description,
        String returnType,
        String plannerType,
        String outputKey,
        List<String> inputKeys,
        String condition,
        Boolean async,
        LoopMetaDto loop,
        List<AgentTopologyNodeDto> children
) {
}
