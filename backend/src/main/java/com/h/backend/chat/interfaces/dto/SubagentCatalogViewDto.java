package com.h.backend.chat.interfaces.dto;

import com.h.backend.chat.domain.subagentdefinition.model.SubagentCatalogView;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionSummary;

import java.time.Instant;
import java.util.List;

/** `GET /api/me/subagents` 的响应：一次返回页面所需事实（设计 9.1）。 */
public record SubagentCatalogViewDto(
        List<SubagentDefinitionSummaryDto> system,
        List<SubagentDefinitionSummaryDto> mine,
        SubagentQuotaDto limits,
        SubagentCapabilityDto capabilities) {

    public static SubagentCatalogViewDto from(SubagentCatalogView view) {
        return new SubagentCatalogViewDto(
                fromSummaries(view.system()),
                fromSummaries(view.mine()),
                SubagentQuotaDto.from(view.limits()),
                SubagentCapabilityDto.from(view.capabilities())
        );
    }

    private static List<SubagentDefinitionSummaryDto> fromSummaries(List<SubagentDefinitionSummary> summaries) {
        if (summaries == null) {
            return List.of();
        }
        return summaries.stream().map(SubagentDefinitionSummaryDto::from).toList();
    }

    public record SubagentDefinitionSummaryDto(
            String agentId,
            String displayName,
            String description,
            String source,
            Long draftRevision,
            Boolean draftValid,
            Integer currentVersion,
            boolean enabled,
            boolean deleted,
            Instant updatedAt) {

        public static SubagentDefinitionSummaryDto from(SubagentDefinitionSummary summary) {
            return new SubagentDefinitionSummaryDto(
                    summary.agentId(),
                    summary.displayName(),
                    summary.description(),
                    summary.source() == null ? null : summary.source().name(),
                    summary.draftRevision(),
                    summary.draftValid(),
                    summary.currentVersion(),
                    summary.enabled(),
                    summary.deleted(),
                    summary.updatedAt()
            );
        }
    }

    public record SubagentQuotaDto(
            int maxDefinitions,
            int maxEnabled,
            long usedDefinitions,
            long usedEnabled) {

        public static SubagentQuotaDto from(SubagentCatalogView.SubagentQuotaUsage usage) {
            if (usage == null) {
                return null;
            }
            return new SubagentQuotaDto(
                    usage.maxDefinitions(),
                    usage.maxEnabled(),
                    usage.usedDefinitions(),
                    usage.usedEnabled()
            );
        }
    }

    public record SubagentCapabilityDto(
            List<String> models,
            List<String> defaultTools,
            List<String> requestableTools) {

        public static SubagentCapabilityDto from(SubagentCatalogView.SubagentCapabilitySummary summary) {
            if (summary == null) {
                return null;
            }
            return new SubagentCapabilityDto(
                    summary.models(),
                    summary.defaultTools(),
                    summary.requestableTools()
            );
        }
    }
}
