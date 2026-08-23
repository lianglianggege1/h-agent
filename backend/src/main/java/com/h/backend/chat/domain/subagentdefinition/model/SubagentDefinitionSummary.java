package com.h.backend.chat.domain.subagentdefinition.model;

import java.time.Instant;

/** 管理列表中的单条定义摘要。 */
public record SubagentDefinitionSummary(
        String agentId,
        String displayName,
        String description,
        SubagentDefinitionSource source,
        Long draftRevision,
        Boolean draftValid,
        Integer currentVersion,
        boolean enabled,
        boolean deleted,
        Instant updatedAt) {
}
