package com.h.backend.chat.interfaces.web;

import com.h.backend.chat.domain.subagentdefinition.SubagentDefinitionException;
import com.h.backend.chat.domain.subagentdefinition.SubagentRateLimitException;
import com.h.backend.chat.interfaces.dto.SubagentErrorBody;
import com.h.backend.chat.interfaces.dto.ValidationIssueDto;
import com.h.backend.common.api.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Subagent 管理接口错误映射（设计 9.3）：
 * NOT_FOUND → 404；CONFLICT 类 → 409；校验/配额类 → 422；请求格式类 → 400；限流 → 429。
 * 响应体继续使用项目 {@link ApiResponse}，data 携带字符串 errorCode 与结构化 issues。
 */
@RestControllerAdvice
public class SubagentDefinitionExceptionAdvisor {

    @ExceptionHandler(SubagentDefinitionException.class)
    public ResponseEntity<ApiResponse<SubagentErrorBody>> handle(SubagentDefinitionException ex) {
        HttpStatus status = statusOf(ex.getErrorCode());
        return ResponseEntity.status(status).body(new ApiResponse<>(
                status.value(),
                ex.getMessage(),
                new SubagentErrorBody(ex.getErrorCode(), ValidationIssueDto.from(ex.getIssues()))
        ));
    }

    @ExceptionHandler(SubagentRateLimitException.class)
    public ResponseEntity<ApiResponse<Void>> handleRateLimit(SubagentRateLimitException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(ApiResponse.error(HttpStatus.TOO_MANY_REQUESTS.value(), ex.getMessage()));
    }

    private static HttpStatus statusOf(String errorCode) {
        if (SubagentDefinitionException.DEFINITION_NOT_FOUND.equals(errorCode)) {
            return HttpStatus.NOT_FOUND;
        }
        if (SubagentDefinitionException.DRAFT_REVISION_CONFLICT.equals(errorCode)
                || SubagentDefinitionException.DEFINITION_ALREADY_EXISTS.equals(errorCode)
                || SubagentDefinitionException.DELETE_REQUIRES_DISABLED.equals(errorCode)
                || SubagentDefinitionException.DEFINITION_DELETED.equals(errorCode)) {
            return HttpStatus.CONFLICT;
        }
        if (SubagentDefinitionException.PUBLISH_VALIDATION_FAILED.equals(errorCode)
                || SubagentDefinitionException.NO_PUBLISHED_VERSION.equals(errorCode)
                || SubagentDefinitionException.DEFINITION_LIMIT_EXCEEDED.equals(errorCode)
                || SubagentDefinitionException.ENABLED_LIMIT_EXCEEDED.equals(errorCode)
                || SubagentDefinitionException.RESERVED_AGENT_ID.equals(errorCode)) {
            return HttpStatus.UNPROCESSABLE_ENTITY;
        }
        // INVALID_AGENT_ID 及未知错误按请求格式类处理。
        return HttpStatus.BAD_REQUEST;
    }
}
