package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationProposal;

/**
 * 提案与聊天时间线的锚点视图。
 * anchorMessageId 为 null 表示 Run 尚未终结，前端暂不展示。
 */
public record AnchoredProposalView(
        AutomationProposal proposal,
        Long sourceAgentRunId,
        String anchorMessageId,
        String anchorPlacement
) {
    public static final String PLACEMENT_AFTER = "AFTER";

    public static AnchoredProposalView after(AutomationProposal proposal, String anchorMessageId) {
        return new AnchoredProposalView(
                proposal,
                proposal.sourceAgentRunId(),
                anchorMessageId,
                PLACEMENT_AFTER
        );
    }

    public static AnchoredProposalView notReady(AutomationProposal proposal) {
        return new AnchoredProposalView(proposal, proposal.sourceAgentRunId(), null, PLACEMENT_AFTER);
    }
}
