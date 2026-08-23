package com.h.backend.chat.domain.subagentdefinition.model;

import java.time.Instant;

/** 版本列表条目。 */
public record SubagentDefinitionVersionSummary(
        int version,
        String contentHash,
        Instant publishedAt,
        boolean current) {
}
