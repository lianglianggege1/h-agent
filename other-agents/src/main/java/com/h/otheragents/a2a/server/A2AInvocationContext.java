package com.h.otheragents.a2a.server;

import java.util.Map;

public record A2AInvocationContext(
        String agentId,
        String contextId,
        String taskId,
        String userId,
        String sessionId,
        String memoryId
) {

    public static A2AInvocationContext fromMetadata(
            String agentId,
            String contextId,
            String taskId,
            Map<String, Object> metadata
    ) {
        return new A2AInvocationContext(
                agentId,
                contextId,
                taskId,
                stringValue(metadata, "userId"),
                stringValue(metadata, "sessionId"),
                stringValue(metadata, "memoryId")
        );
    }

    public String memoryKey() {
        if (memoryId != null && !memoryId.isBlank()) {
            return memoryId;
        }
        if (sessionId != null && !sessionId.isBlank()) {
            return sessionId;
        }
        return contextId;
    }

    private static String stringValue(Map<String, Object> metadata, String key) {
        if (metadata == null) {
            return null;
        }
        Object value = metadata.get(key);
        return value == null ? null : value.toString();
    }
}
