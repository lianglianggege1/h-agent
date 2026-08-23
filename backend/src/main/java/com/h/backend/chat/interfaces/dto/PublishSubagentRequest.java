package com.h.backend.chat.interfaces.dto;

import jakarta.validation.constraints.NotNull;

/** 发布请求体（设计 9.2）：只提交 expectedRevision，发布数据库中该 revision 的草稿。 */
public record PublishSubagentRequest(
        @NotNull(message = "expectedRevision 不能为空") Long expectedRevision) {
}
