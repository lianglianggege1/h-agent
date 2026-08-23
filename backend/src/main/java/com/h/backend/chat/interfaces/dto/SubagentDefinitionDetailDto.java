package com.h.backend.chat.interfaces.dto;

import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionDetail;

import java.time.Instant;
import java.util.List;

/** 定义详情：当前草稿与当前发布版本的 Markdown（设计 9.1 / 编辑页数据源）。 */
public record SubagentDefinitionDetailDto(
        String agentId,
        long definitionId,
        String source,
        Integer currentVersion,
        String currentMarkdown,
        String currentContentHash,
        boolean enabled,
        boolean deleted,
        Long draftRevision,
        String draftMarkdown,
        List<ValidationIssueDto> draftIssues,
        Instant createdAt,
        Instant updatedAt) {

    public static SubagentDefinitionDetailDto from(SubagentDefinitionDetail detail) {
        return new SubagentDefinitionDetailDto(
                detail.agentId(),
                detail.definitionId(),
                detail.source() == null ? null : detail.source().name(),
                detail.currentVersion(),
                detail.currentMarkdown(),
                detail.currentContentHash(),
                detail.enabled(),
                detail.deleted(),
                detail.draftRevision(),
                detail.draftMarkdown(),
                ValidationIssueDto.from(detail.draftIssues()),
                detail.createdAt(),
                detail.updatedAt()
        );
    }
}
