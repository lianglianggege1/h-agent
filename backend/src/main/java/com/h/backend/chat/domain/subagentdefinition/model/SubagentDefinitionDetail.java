package com.h.backend.chat.domain.subagentdefinition.model;

import java.time.Instant;
import java.util.List;

/** 详情视图：包含当前草稿与当前发布版本的 Markdown。 */
public record SubagentDefinitionDetail(
        String agentId,
        long definitionId,
        SubagentDefinitionSource source,
        Integer currentVersion,
        String currentMarkdown,
        String currentContentHash,
        boolean enabled,
        boolean deleted,
        Long draftRevision,
        String draftMarkdown,
        List<ValidationIssue> draftIssues,
        Instant createdAt,
        Instant updatedAt) {
}
