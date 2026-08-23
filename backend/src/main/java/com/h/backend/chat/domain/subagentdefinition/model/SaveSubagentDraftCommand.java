package com.h.backend.chat.domain.subagentdefinition.model;

/** 保存草稿的命令；expectedRevision 过期时返回冲突，不覆盖。 */
public record SaveSubagentDraftCommand(long expectedRevision, String markdown) {
}
