package com.h.backend.chat.domain.subagentdefinition.model;

/** 创建用户定义草稿的命令；agentId 创建后不可修改。 */
public record CreateSubagentDraftCommand(String agentId, String markdown) {
}
