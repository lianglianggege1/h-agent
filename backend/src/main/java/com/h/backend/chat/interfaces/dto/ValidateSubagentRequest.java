package com.h.backend.chat.interfaces.dto;

import jakarta.validation.constraints.NotBlank;

/** 独立校验请求体（设计 9.2）；不落库。 */
public record ValidateSubagentRequest(
        @NotBlank(message = "markdown 不能为空") String markdown) {
}
