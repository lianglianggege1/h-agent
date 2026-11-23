package com.h.backend.automation.interfaces.dto;

import com.h.backend.automation.application.AnchoredProposalView;
import com.h.backend.automation.domain.AutomationProposal;

import java.time.Instant;
import java.util.List;

/**
 * 提案视图：status 为 PENDING 时携带未来 3 次触发时刻供确认页展示；
 * name/cron 等字段仅 CREATE/UPDATE 提案存在。
 * sourceAgentRunId、anchorMessageId、anchorPlacement 用于聊天时间线原位渲染。
 */
public record AutomationProposalDto(
        String id,
        String action,
        String taskId,
        String status,
        String sourceSessionId,
        String executionSessionId,
        String createdVia,
        Instant createdAt,
        Instant updatedAt,
        Instant expiresAt,
        Instant confirmedAt,
        String resultTaskId,
        String name,
        String instruction,
        String agentId,
        String cronExpression,
        String zoneId,
        String deliverySink,
        List<Instant> upcomingFires,
        Long sourceAgentRunId,
        String anchorMessageId,
        String anchorPlacement
) {
    private static String executionSession(AutomationProposal proposal) {
        if (proposal.payloadJson() != null) {
            try {
                String session = new tools.jackson.databind.ObjectMapper().readTree(proposal.payloadJson())
                        .path("executionSessionId").asString("");
                if (!session.isBlank()) return session;
            } catch (RuntimeException ignored) {
                // Older status-only proposals have no execution parameters.
            }
        }
        return proposal.sourceSessionId();
    }

    public static AutomationProposalDto from(AutomationProposal proposal, List<Instant> upcomingFires) {
        return new AutomationProposalDto(
                proposal.id(), proposal.action(), proposal.taskId(), proposal.status(),
                proposal.sourceSessionId(), executionSession(proposal), proposal.createdVia(), proposal.createdAt(), proposal.updatedAt(),
                proposal.expiresAt(), proposal.confirmedAt(), proposal.resultTaskId(),
                null, null, null, null, null, null, upcomingFires,
                proposal.sourceAgentRunId(), null, AnchoredProposalView.PLACEMENT_AFTER
        );
    }

    public static AutomationProposalDto from(
            AutomationProposal proposal,
            String name,
            String instruction,
            String agentId,
            String cronExpression,
            String zoneId,
            String deliverySink,
            List<Instant> upcomingFires
    ) {
        return new AutomationProposalDto(
                proposal.id(), proposal.action(), proposal.taskId(), proposal.status(),
                proposal.sourceSessionId(), executionSession(proposal), proposal.createdVia(), proposal.createdAt(), proposal.updatedAt(),
                proposal.expiresAt(), proposal.confirmedAt(), proposal.resultTaskId(),
                name, instruction, agentId, cronExpression, zoneId, deliverySink, upcomingFires,
                proposal.sourceAgentRunId(), null, AnchoredProposalView.PLACEMENT_AFTER
        );
    }

    public static AutomationProposalDto from(
            AnchoredProposalView view,
            String name,
            String instruction,
            String agentId,
            String cronExpression,
            String zoneId,
            String deliverySink,
            List<Instant> upcomingFires
    ) {
        AutomationProposal proposal = view.proposal();
        return new AutomationProposalDto(
                proposal.id(), proposal.action(), proposal.taskId(), proposal.status(),
                proposal.sourceSessionId(), executionSession(proposal), proposal.createdVia(), proposal.createdAt(), proposal.updatedAt(),
                proposal.expiresAt(), proposal.confirmedAt(), proposal.resultTaskId(),
                name, instruction, agentId, cronExpression, zoneId, deliverySink, upcomingFires,
                view.sourceAgentRunId(), view.anchorMessageId(), view.anchorPlacement()
        );
    }
}
