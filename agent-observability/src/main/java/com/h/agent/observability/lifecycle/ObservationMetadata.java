package com.h.agent.observability.lifecycle;

import java.util.List;

public record ObservationMetadata(
        String sessionId,
        String userId,
        String traceName,
        List<String> tags,
        String rootRunId,
        String entryKind,
        String agentSessionId
) {

    public static ObservationMetadata empty() {
        return new ObservationMetadata(null, null, null, List.of(), null, null, null);
    }
}
