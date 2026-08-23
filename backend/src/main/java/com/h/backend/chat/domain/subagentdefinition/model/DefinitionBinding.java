package com.h.backend.chat.domain.subagentdefinition.model;

/** 一次协作 Agent Session 永久绑定的版本身份。 */
public record DefinitionBinding(long definitionId, int version) {

    public static DefinitionBinding of(long definitionId, int version) {
        return new DefinitionBinding(definitionId, version);
    }
}
