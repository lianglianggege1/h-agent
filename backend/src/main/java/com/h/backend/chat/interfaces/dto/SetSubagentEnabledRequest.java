package com.h.backend.chat.interfaces.dto;

import jakarta.validation.constraints.NotNull;

/** 启用/停用请求体（设计 9.2）。 */
public record SetSubagentEnabledRequest(
        @NotNull(message = "enabled 不能为空") Boolean enabled) {
}
