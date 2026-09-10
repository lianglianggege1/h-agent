package com.h.backend.automation.infrastructure.persistence;

import com.h.backend.automation.application.AnchoredProposalView;
import com.h.backend.automation.application.AutomationProposalRepository;
import com.h.backend.automation.domain.AutomationProposal;
import com.h.backend.automation.infrastructure.persistence.entity.AutomationProposalEntity;
import com.h.backend.automation.infrastructure.persistence.mapper.AutomationProposalMapper;
import com.h.backend.automation.infrastructure.persistence.mapper.AutomationProposalMapper.AnchoredProposalRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Repository
public class AutomationProposalRepositoryImpl implements AutomationProposalRepository {

    private static final Logger log = LoggerFactory.getLogger(AutomationProposalRepositoryImpl.class);
    private static final Set<String> TERMINAL_RUN_STATUSES = Set.of("SUCCEEDED", "FAILED", "CANCELLED");

    private final AutomationProposalMapper mapper;

    public AutomationProposalRepositoryImpl(AutomationProposalMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public AutomationProposal insert(AutomationProposal proposal) {
        mapper.insert(toEntity(proposal));
        return proposal;
    }

    @Override
    public Optional<AutomationProposal> findOwned(Long userId, String proposalId) {
        return Optional.ofNullable(mapper.selectOwned(userId, proposalId)).map(this::toDomain);
    }

    @Override
    public Optional<AutomationProposal> findOwnedForUpdate(Long userId, String proposalId) {
        return Optional.ofNullable(mapper.selectOwnedForUpdate(userId, proposalId)).map(this::toDomain);
    }

    @Override
    public List<AutomationProposal> listPendingOwned(Long userId, Instant now) {
        return mapper.selectPendingOwned(userId, toLocal(now)).stream().map(this::toDomain).toList();
    }

    @Override
    public List<AutomationProposal> listOwnedBySession(Long userId, String sessionId) {
        return mapper.selectOwnedBySession(userId, sessionId).stream().map(this::toDomain).toList();
    }

    @Override
    public List<AnchoredProposalView> listAnchoredBySession(Long userId, String sessionId) {
        List<AnchoredProposalRow> rows = mapper.selectAnchoredBySession(userId, sessionId);
        List<AnchoredProposalView> result = new ArrayList<>(rows.size());
        for (AnchoredProposalRow row : rows) {
            if (!userId.equals(row.getUserId()) || !sessionId.equals(row.getSourceSessionId())) {
                log.warn("Proposal ownership mismatch: proposalId={}, userId={}, sessionId={}",
                        row.getId(), row.getUserId(), row.getSourceSessionId());
                continue;
            }
            if (row.getSourceAgentRunId() != null && row.getRunSessionId() != null
                    && !sessionId.equals(row.getRunSessionId())) {
                log.warn("Proposal run session mismatch: proposalId={}, runSessionId={}",
                        row.getId(), row.getRunSessionId());
                continue;
            }
            AutomationProposal proposal = toDomain(row);
            String anchorMessageId = resolveAnchorMessageId(row);
            result.add(AnchoredProposalView.after(proposal, anchorMessageId));
        }
        return result;
    }

    private String resolveAnchorMessageId(AnchoredProposalRow row) {
        if (row.getSourceAgentRunId() == null || row.getRunStatus() == null) {
            return null;
        }
        if (!TERMINAL_RUN_STATUSES.contains(row.getRunStatus())) {
            return null;
        }
        if ("SUCCEEDED".equals(row.getRunStatus()) && row.getRunAssistantMessageId() != null) {
            return String.valueOf(row.getRunAssistantMessageId());
        }
        return row.getRunUserMessageId() != null ? String.valueOf(row.getRunUserMessageId()) : null;
    }

    @Override
    public AutomationProposal markConfirmed(String proposalId, String resultTaskId, Long confirmedBy, Instant now) {
        AutomationProposalEntity entity = mapper.markConfirmed(
                proposalId, resultTaskId, confirmedBy, toLocal(now));
        return entity == null ? null : toDomain(entity);
    }

    @Override
    public AutomationProposal markDiscarded(Long userId, String proposalId, Instant now) {
        AutomationProposalEntity entity = mapper.markDiscarded(userId, proposalId, toLocal(now));
        return entity == null ? null : toDomain(entity);
    }

    @Override
    public int markExpired(Instant now) {
        return mapper.markExpired(toLocal(now));
    }

    private AutomationProposalEntity toEntity(AutomationProposal proposal) {
        AutomationProposalEntity entity = new AutomationProposalEntity();
        entity.setId(proposal.id());
        entity.setUserId(proposal.userId());
        entity.setTaskId(proposal.taskId());
        entity.setAction(proposal.action());
        entity.setPayloadJson(proposal.payloadJson());
        entity.setBaseTaskRevision(proposal.baseTaskRevision());
        entity.setStatus(proposal.status());
        entity.setSourceSessionId(proposal.sourceSessionId());
        entity.setCreatedVia(proposal.createdVia());
        entity.setIdempotencyKey(proposal.idempotencyKey());
        entity.setResultTaskId(proposal.resultTaskId());
        entity.setSourceAgentRunId(proposal.sourceAgentRunId());
        entity.setExpiresAt(toLocal(proposal.expiresAt()));
        entity.setConfirmedAt(toLocal(proposal.confirmedAt()));
        entity.setConfirmedBy(proposal.confirmedBy());
        entity.setCreatedAt(toLocal(proposal.createdAt()));
        entity.setUpdatedAt(toLocal(proposal.updatedAt()));
        return entity;
    }

    private AutomationProposal toDomain(AutomationProposalEntity entity) {
        return new AutomationProposal(
                entity.getId(), entity.getUserId(), entity.getTaskId(), entity.getAction(),
                entity.getPayloadJson(), entity.getBaseTaskRevision(), entity.getStatus(),
                entity.getSourceSessionId(), entity.getCreatedVia(), entity.getIdempotencyKey(),
                entity.getResultTaskId(), entity.getSourceAgentRunId(), toInstant(entity.getExpiresAt()),
                toInstant(entity.getConfirmedAt()), entity.getConfirmedBy(),
                toInstant(entity.getCreatedAt()), toInstant(entity.getUpdatedAt())
        );
    }

    private AutomationProposal toDomain(AnchoredProposalRow row) {
        return new AutomationProposal(
                row.getId(), row.getUserId(), row.getTaskId(), row.getAction(),
                row.getPayloadJson(), row.getBaseTaskRevision(), row.getStatus(),
                row.getSourceSessionId(), row.getCreatedVia(), row.getIdempotencyKey(),
                row.getResultTaskId(), row.getSourceAgentRunId(), toInstant(row.getExpiresAt()),
                toInstant(row.getConfirmedAt()), row.getConfirmedBy(),
                toInstant(row.getCreatedAt()), toInstant(row.getUpdatedAt())
        );
    }

    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
