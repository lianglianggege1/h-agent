package com.h.backend.chat.domain.subagentdefinition.model;

/** 发布结果：带 version、hash、enabled 和编译后的 capability summary。 */
public record PublishResult(
        String agentId,
        long definitionId,
        int version,
        String contentHash,
        boolean enabled,
        long revision,
        CompiledSubagentDefinition compiled) {
}
