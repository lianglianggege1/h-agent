package com.h.backend.chat.domain.subagentdefinition.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Markdown 定义经平台校验后的不可变编译结果；持久化为 compiled_metadata_json。
 *
 * <p>本期 model 只支持 inherit、mode 只支持 subagent；这两个字段保存为显式值
 * 是为了让未来扩展不需要破坏已发布版本的存储格式。</p>
 */
public record CompiledSubagentDefinition(
        @JsonProperty("displayName") String displayName,
        @JsonProperty("description") String description,
        @JsonProperty("mode") String mode,
        @JsonProperty("model") String model,
        @JsonProperty("steps") int steps,
        @JsonProperty("tools") CapabilityDeclaration tools,
        @JsonProperty("skills") CapabilityDeclaration skills,
        @JsonProperty("workspaceMode") SubagentWorkspaceMode workspaceMode,
        @JsonProperty("runtimeKind") SubagentRuntimeKind runtimeKind,
        @JsonProperty("systemPrompt") String systemPrompt) {

    public static final String MODEL_INHERIT = "inherit";
    public static final String MODE_SUBAGENT = "subagent";
}
