package com.h.backend.chat.domain.subagentdefinition.model;

import java.util.List;

/**
 * Markdown 编译结果。
 *
 * <p>issues 无 ERROR 时 compiled 与 contentHash 非空；有 ERROR 时二者为 null，
 * 调用方不得使用部分编译结果。</p>
 *
 * @param issues        结构化校验问题（ERROR 阻止发布，WARNING 仅提示）
 * @param compiled      规范化编译结果；存在 ERROR 时为 null
 * @param contentHash   规范化原文 SHA-256；存在 ERROR 时为 null
 * @param normalized    换行规范化后的原文（hash 的输入）；存在 ERROR 时为 null
 */
public record CompileOutcome(
        List<ValidationIssue> issues,
        CompiledSubagentDefinition compiled,
        String contentHash,
        String normalized) {

    public CompileOutcome {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public boolean hasErrors() {
        return issues.stream().anyMatch(i -> i.severity() == ValidationIssue.Severity.ERROR);
    }

    public static CompileOutcome failed(List<ValidationIssue> issues) {
        return new CompileOutcome(issues, null, null, null);
    }
}
