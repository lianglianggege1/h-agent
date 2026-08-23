package com.h.backend.chat.domain.subagentdefinition.model;

import java.util.List;

/** 草稿保存/创建结果：始终带新 revision 与 validation issues。 */
public record DraftResult(
        String agentId,
        long definitionId,
        long revision,
        List<ValidationIssue> issues) {

    public DraftResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean hasErrors() {
        return issues.stream()
                .anyMatch(issue -> issue.severity() == ValidationIssue.Severity.ERROR);
    }
}
