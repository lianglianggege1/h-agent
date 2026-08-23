package com.h.backend.chat.domain.subagentdefinition.model;

/**
 * 结构化校验问题；发布错误必须能定位字段，解析器能确定位置时同时给出行列。
 *
 * @param code    稳定错误码，如 UNKNOWN_FIELD / INVALID_TYPE / LENGTH_EXCEEDED
 * @param severity ERROR 阻止发布；WARNING 仅提示
 * @param field   出错字段路径，如 {@code tools[2]}、{@code front-matter}
 * @param line    front matter 或正文中 1 起始的行号；未知为 null
 * @param column  1 起始的列号；未知为 null
 * @param message 面向用户的中文消息
 */
public record ValidationIssue(
        String code,
        Severity severity,
        String field,
        Integer line,
        Integer column,
        String message) {

    public enum Severity {
        ERROR,
        WARNING
    }

    public static ValidationIssue error(String code, String field, String message) {
        return new ValidationIssue(code, Severity.ERROR, field, null, null, message);
    }

    public static ValidationIssue error(String code, String field, Integer line, String message) {
        return new ValidationIssue(code, Severity.ERROR, field, line, null, message);
    }

    public static ValidationIssue error(String code, String field, Integer line, Integer column, String message) {
        return new ValidationIssue(code, Severity.ERROR, field, line, column, message);
    }

    public static ValidationIssue warning(String code, String field, String message) {
        return new ValidationIssue(code, Severity.WARNING, field, null, null, message);
    }

    public static ValidationIssue warning(String code, String field, Integer line, String message) {
        return new ValidationIssue(code, Severity.WARNING, field, line, null, message);
    }
}
