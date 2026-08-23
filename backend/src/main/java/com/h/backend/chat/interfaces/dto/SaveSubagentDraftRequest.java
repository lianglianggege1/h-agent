package com.h.backend.chat.interfaces.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** 保存草稿的请求体（设计 9.2）；expectedRevision 过期返回 409，不覆盖。 */
public record SaveSubagentDraftRequest(
        @NotNull(message = "expectedRevision 不能为空") Long expectedRevision,
        @NotBlank(message = "markdown 不能为空") String markdown) {
}
