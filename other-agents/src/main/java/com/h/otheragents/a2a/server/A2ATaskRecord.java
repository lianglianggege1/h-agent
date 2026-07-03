package com.h.otheragents.a2a.server;

import java.time.Instant;

public record A2ATaskRecord(
        String taskId,
        String contextId,
        String agentId,
        String state,
        String lastText,
        Instant updatedAt
) {
}
