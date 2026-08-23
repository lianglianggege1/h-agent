package com.h.backend.chat.interfaces.dto;

import java.util.List;

/** Subagent 管理接口错误体：errorCode 是接口契约（设计 9.3），issues 仅校验类错误非空。 */
public record SubagentErrorBody(String errorCode, List<ValidationIssueDto> issues) {
}
