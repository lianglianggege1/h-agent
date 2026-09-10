package com.h.backend.automation.interfaces.dto;

import com.h.backend.automation.domain.AutomationProposal;

import java.time.Instant;
import java.util.List;

/**
 * 提案视图：status 为 PENDING 时携带未来 3 次触发时刻供确认页展示；
 * name/cron 等字段仅 CREATE/UPDATE 提案存在。
 */
public record AutomationProposalDto(
        String id,
        String action,
        String taskId,
        String status,
        String sourceSessionId,
        String createdVia,
        Instant createdAt,
        Instant expiresAt,
        Instant confirmedAt,
        String resultTaskId,
        String name,
        String instruction,
        String agentId,
        String cronExpression,
        String zoneId,
        String deliverySink,
        List<Instant> upcomingFires
) {
    public static AutomationProposalDto from(AutomationProposal proposal, List<Instant> upcomingFires) {
        return new AutomationProposalDto(
                proposal.id(), proposal.action(), proposal.taskId(), proposal.status(),
                proposal.sourceSessionId(), proposal.createdVia(), proposal.createdAt(),
                proposal.expiresAt(), proposal.confirmedAt(), proposal.resultTaskId(),
                null, null, null, null, null, null, upcomingFires
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
                proposal.sourceSessionId(), proposal.createdVia(), proposal.createdAt(),
                proposal.expiresAt(), proposal.confirmedAt(), proposal.resultTaskId(),
                name, instruction, agentId, cronExpression, zoneId, deliverySink, upcomingFires
        );
    }
}
