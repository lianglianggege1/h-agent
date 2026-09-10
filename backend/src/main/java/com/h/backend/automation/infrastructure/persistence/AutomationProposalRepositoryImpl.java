package com.h.backend.automation.infrastructure.persistence;

import com.h.backend.automation.application.AutomationProposalRepository;
import com.h.backend.automation.domain.AutomationProposal;
import com.h.backend.automation.infrastructure.persistence.entity.AutomationProposalEntity;
import com.h.backend.automation.infrastructure.persistence.mapper.AutomationProposalMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

@Repository
public class AutomationProposalRepositoryImpl implements AutomationProposalRepository {

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
                entity.getResultTaskId(), toInstant(entity.getExpiresAt()),
                toInstant(entity.getConfirmedAt()), entity.getConfirmedBy(),
                toInstant(entity.getCreatedAt()), toInstant(entity.getUpdatedAt())
        );
    }

    private static LocalDateTime toLocal(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
