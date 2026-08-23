package com.h.backend.chat.interfaces.dto;

import com.h.backend.chat.domain.subagentdefinition.model.ValidationResult;

import java.util.List;

/** 独立校验结果。 */
public record SubagentValidationResultDto(List<ValidationIssueDto> issues) {

    public static SubagentValidationResultDto from(ValidationResult result) {
        return new SubagentValidationResultDto(ValidationIssueDto.from(result.issues()));
    }
}
