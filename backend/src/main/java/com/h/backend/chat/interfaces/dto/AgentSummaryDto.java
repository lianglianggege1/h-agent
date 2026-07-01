package com.h.backend.chat.interfaces.dto;

import java.util.List;

public record AgentSummaryDto(
        String agentId,
        String displayName,
        String domain,
        List<String> tags,
        String summary,
        String runtimeType,
        boolean enabled
) {
}
