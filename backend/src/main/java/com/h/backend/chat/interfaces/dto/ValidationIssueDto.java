package com.h.backend.chat.interfaces.dto;

import com.h.backend.chat.domain.subagentdefinition.model.ValidationIssue;

/** 结构化校验问题的接口层投影（设计 9.2：保存结果始终带 validation issues）。 */
public record ValidationIssueDto(
        String code,
        String severity,
        String field,
        Integer line,
        Integer column,
        String message) {

    public static ValidationIssueDto from(ValidationIssue issue) {
        if (issue == null) {
            return null;
        }
        return new ValidationIssueDto(
                issue.code(),
                issue.severity() == null ? null : issue.severity().name(),
                issue.field(),
                issue.line(),
                issue.column(),
                issue.message()
        );
    }

    public static java.util.List<ValidationIssueDto> from(java.util.List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return java.util.List.of();
        }
        return issues.stream().map(ValidationIssueDto::from).toList();
    }
}
