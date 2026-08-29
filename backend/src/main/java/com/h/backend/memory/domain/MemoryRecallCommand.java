package com.h.backend.memory.domain;

import java.util.Set;

public record MemoryRecallCommand(
        MemoryInvocationContext context,
        Set<MemoryScopeKind> scopes,
        String query
) {

    public MemoryRecallCommand {
        if (context == null) {
            throw new IllegalArgumentException("context is required");
        }
        if (scopes == null) {
            scopes = Set.of();
        } else {
            scopes = Set.copyOf(scopes);
        }
    }
}
