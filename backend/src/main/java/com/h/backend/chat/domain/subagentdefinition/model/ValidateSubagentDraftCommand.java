package com.h.backend.chat.domain.subagentdefinition.model;

/** 校验草稿的命令；不落库。 */
public record ValidateSubagentDraftCommand(String markdown) {
}
