package com.h.backend.chat.domain.subagentdefinition.model;

/**
 * 已解析到具体版本的定义；turn snapshot 与 pinned follow-up 共用。
 *
 * @param definitionId 数据库定义身份
 * @param agentId      父模型可见的稳定逻辑 ID
 * @param source       BUILTIN / USER
 * @param version      固定的发布版本号
 * @param contentHash  规范化原文 SHA-256，用于日志与观测
 * @param compiled     发布时的编译结果
 */
public record ResolvedSubagentDefinition(
        long definitionId,
        String agentId,
        SubagentDefinitionSource source,
        int version,
        String contentHash,
        CompiledSubagentDefinition compiled) {

    public DefinitionBinding binding() {
        return new DefinitionBinding(definitionId, version);
    }
}
