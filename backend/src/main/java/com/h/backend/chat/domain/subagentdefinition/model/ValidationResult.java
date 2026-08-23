package com.h.backend.chat.domain.subagentdefinition.model;

import java.util.List;

/** 独立校验结果。 */
public record ValidationResult(List<ValidationIssue> issues) {

    public ValidationResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean hasErrors() {
        return issues.stream()
                .anyMatch(issue -> issue.severity() == ValidationIssue.Severity.ERROR);
    }

    public static ValidationResult of(List<ValidationIssue> issues) {
        return new ValidationResult(issues);
    }
}
