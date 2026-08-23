package com.h.backend.chat.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

/** 创建用户定义草稿的请求体（设计 9.2）；agentId 创建后不可修改。 */
public record CreateSubagentRequest(
        @NotBlank(message = "agentId 不能为空") String agentId,
        @NotBlank(message = "markdown 不能为空") String markdown) {
}
