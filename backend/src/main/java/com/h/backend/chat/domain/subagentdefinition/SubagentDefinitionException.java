package com.h.backend.chat.domain.subagentdefinition;

import com.h.backend.chat.domain.subagentdefinition.model.ValidationIssue;

import java.util.List;

/**
 * Subagent 定义目录领域异常；errorCode 是接口契约的一部分（设计 9.3）。
 *
 * <p>HTTP 状态映射由接口层完成：NOT_FOUND → 404、CONFLICT 类 → 409、
 * 校验/配额类 → 422、请求格式类 → 400。消息与 issue 不携带完整 Markdown。</p>
 */
public class SubagentDefinitionException extends RuntimeException {

    public static final String INVALID_AGENT_ID = "INVALID_AGENT_ID";
    public static final String RESERVED_AGENT_ID = "RESERVED_AGENT_ID";
    public static final String DEFINITION_NOT_FOUND = "DEFINITION_NOT_FOUND";
    public static final String DEFINITION_ALREADY_EXISTS = "DEFINITION_ALREADY_EXISTS";
    public static final String DRAFT_REVISION_CONFLICT = "DRAFT_REVISION_CONFLICT";
    public static final String PUBLISH_VALIDATION_FAILED = "PUBLISH_VALIDATION_FAILED";
    public static final String NO_PUBLISHED_VERSION = "NO_PUBLISHED_VERSION";
    public static final String DEFINITION_LIMIT_EXCEEDED = "DEFINITION_LIMIT_EXCEEDED";
    public static final String ENABLED_LIMIT_EXCEEDED = "ENABLED_LIMIT_EXCEEDED";
    public static final String DELETE_REQUIRES_DISABLED = "DELETE_REQUIRES_DISABLED";
    public static final String DEFINITION_DELETED = "DEFINITION_DELETED";

    private final String errorCode;
    private final List<ValidationIssue> issues;

    public SubagentDefinitionException(String errorCode, String message) {
        this(errorCode, message, List.of());
    }

    public SubagentDefinitionException(String errorCode, String message, List<ValidationIssue> issues) {
        super(message);
        this.errorCode = errorCode;
        this.issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public String getErrorCode() {
        return errorCode;
    }

    /** 结构化校验问题；仅 PUBLISH_VALIDATION_FAILED 等场景非空。 */
    public List<ValidationIssue> getIssues() {
        return issues;
    }
}
