package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationProposal;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface AutomationProposalRepository {

    AutomationProposal insert(AutomationProposal proposal);

    Optional<AutomationProposal> findOwned(Long userId, String proposalId);

    /** 在当前事务内锁定提案，供确认流程原子地判定并应用一次。 */
    default Optional<AutomationProposal> findOwnedForUpdate(Long userId, String proposalId) {
        return findOwned(userId, proposalId);
    }

    List<AutomationProposal> listPendingOwned(Long userId, Instant now);

    default List<AutomationProposal> listOwnedBySession(Long userId, String sessionId) {
        return List.of();
    }

    /** 查询会话内提案及其 AgentRun 锚点，用于聊天时间线原位渲染。 */
    default List<AnchoredProposalView> listAnchoredBySession(Long userId, String sessionId) {
        return List.of();
    }

    /** 确认提案：仅 PENDING 且未过期可流转，返回更新后的提案；重复确认返回首次结果。 */
    AutomationProposal markConfirmed(String proposalId, String resultTaskId, Long confirmedBy, Instant now);

    AutomationProposal markDiscarded(Long userId, String proposalId, Instant now);

    int markExpired(Instant now);
}
