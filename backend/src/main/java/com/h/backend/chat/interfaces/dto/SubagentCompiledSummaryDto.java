package com.h.backend.chat.interfaces.dto;

import com.h.backend.chat.domain.subagentdefinition.model.CapabilityDeclaration;
import com.h.backend.chat.domain.subagentdefinition.model.CompiledSubagentDefinition;

/** 编译后的 capability 摘要：不含 system prompt 正文（设计 9.2）。 */
public record SubagentCompiledSummaryDto(
        String displayName,
        String description,
        String mode,
        String model,
        int steps,
        CapabilityDto tools,
        CapabilityDto skills,
        String workspaceMode,
        String runtimeKind) {

    public static SubagentCompiledSummaryDto from(CompiledSubagentDefinition compiled) {
        if (compiled == null) {
            return null;
        }
        return new SubagentCompiledSummaryDto(
                compiled.displayName(),
                compiled.description(),
                compiled.mode(),
                compiled.model(),
                compiled.steps(),
                CapabilityDto.from(compiled.tools()),
                CapabilityDto.from(compiled.skills()),
                compiled.workspaceMode() == null ? null : compiled.workspaceMode().name(),
                compiled.runtimeKind() == null ? null : compiled.runtimeKind().name()
        );
    }

    /** 声明语义原样透出：OMITTED / EMPTY / EXPLICIT 三种语义由前端展示（设计 5.4）。 */
    public record CapabilityDto(String kind, java.util.List<String> names) {

        public static CapabilityDto from(CapabilityDeclaration declaration) {
            if (declaration == null) {
                return null;
            }
            return new CapabilityDto(
                    declaration.kind() == null ? null : declaration.kind().name(),
                    declaration.names()
            );
        }
    }
}
