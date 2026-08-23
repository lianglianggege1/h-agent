package com.h.backend.chat.domain.subagentdefinition.model;

import java.time.Instant;

/** 版本详情：只读 Markdown 预览与编译结果。 */
public record SubagentDefinitionVersionDetail(
        String agentId,
        long definitionId,
        int version,
        String contentHash,
        String markdown,
        Instant publishedAt,
        boolean current,
        CompiledSubagentDefinition compiled) {
}
