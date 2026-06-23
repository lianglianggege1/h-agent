package com.h.backend.chat.dto;

import java.util.List;

public record AgentTopologyDto(
        AgentSummaryDto agent,
        AgentTopologyNodeDto root,
        List<StateKeyDto> stateKeys
) {
}
