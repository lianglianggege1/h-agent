package com.h.backend.chat.interfaces.dto;

import com.h.backend.chat.domain.subagentdefinition.model.DraftResult;

import java.util.List;

/** 草稿创建/保存结果：始终带新 revision 与 validation issues（设计 9.2）。 */
public record SubagentDraftResultDto(
        String agentId,
        long definitionId,
        long revision,
        List<ValidationIssueDto> issues) {

    public static SubagentDraftResultDto from(DraftResult result) {
        return new SubagentDraftResultDto(
                result.agentId(),
                result.definitionId(),
                result.revision(),
                ValidationIssueDto.from(result.issues())
        );
    }
}
