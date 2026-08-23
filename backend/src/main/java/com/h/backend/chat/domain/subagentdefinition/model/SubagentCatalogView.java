package com.h.backend.chat.domain.subagentdefinition.model;

import java.util.List;

/** 管理页一次返回的事实集合。 */
public record SubagentCatalogView(
        List<SubagentDefinitionSummary> system,
        List<SubagentDefinitionSummary> mine,
        SubagentQuotaUsage limits,
        SubagentCapabilitySummary capabilities) {

    public record SubagentQuotaUsage(
            int maxDefinitions,
            int maxEnabled,
            long usedDefinitions,
            long usedEnabled) {
    }

    public record SubagentCapabilitySummary(
            List<String> models,
            List<String> defaultTools,
            List<String> requestableTools) {
    }
}
