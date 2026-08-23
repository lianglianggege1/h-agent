package com.h.backend.chat.interfaces.dto;

import com.h.backend.chat.domain.subagentdefinition.model.PublishResult;

/** 发布结果：version、hash、enabled 与编译后 capability 摘要（设计 9.2）。 */
public record SubagentPublishResultDto(
        String agentId,
        long definitionId,
        int version,
        String contentHash,
        boolean enabled,
        long revision,
        SubagentCompiledSummaryDto compiled) {

    public static SubagentPublishResultDto from(PublishResult result) {
        return new SubagentPublishResultDto(
                result.agentId(),
                result.definitionId(),
                result.version(),
                result.contentHash(),
                result.enabled(),
                result.revision(),
                SubagentCompiledSummaryDto.from(result.compiled())
        );
    }
}
