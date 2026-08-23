package com.h.backend.chat.interfaces.dto;

import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionVersionDetail;

import java.time.Instant;

/** 版本详情：只读 Markdown 预览与编译结果（设计 9.1）。 */
public record SubagentVersionDetailDto(
        String agentId,
        long definitionId,
        int version,
        String contentHash,
        String markdown,
        Instant publishedAt,
        boolean current,
        SubagentCompiledSummaryDto compiled) {

    public static SubagentVersionDetailDto from(SubagentDefinitionVersionDetail detail) {
        return new SubagentVersionDetailDto(
                detail.agentId(),
                detail.definitionId(),
                detail.version(),
                detail.contentHash(),
                detail.markdown(),
                detail.publishedAt(),
                detail.current(),
                SubagentCompiledSummaryDto.from(detail.compiled())
        );
    }
}
