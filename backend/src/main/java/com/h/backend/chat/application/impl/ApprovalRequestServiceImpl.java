package com.h.backend.chat.application.impl;

import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.ApprovalRequestService;
import com.h.backend.chat.domain.approval.ApprovalDecision;
import com.h.backend.chat.domain.approval.ApprovalMode;
import com.h.backend.chat.domain.approval.ApprovalRequestStatus;
import com.h.backend.chat.infrastructure.persistence.entity.ApprovalRequestEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ApprovalRequestMapper;
import com.h.backend.chat.interfaces.dto.ApprovalActionDto;
import com.h.backend.chat.interfaces.dto.ApprovalRequestDto;
import com.h.backend.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ApprovalRequestServiceImpl implements ApprovalRequestService {

    private final ApprovalRequestMapper approvalRequestMapper;
    private final AgentRunService agentRunService;
    private final ObjectMapper objectMapper;

    public ApprovalRequestServiceImpl(
            ApprovalRequestMapper approvalRequestMapper,
            AgentRunService agentRunService,
            ObjectMapper objectMapper
    ) {
        this.approvalRequestMapper = approvalRequestMapper;
        this.agentRunService = agentRunService;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ApprovalRequestDto suspend(SuspendApprovalCommand command) {
        ApprovalRequestEntity existing = approvalRequestMapper.selectByRunAndRequestKey(
                command.runId(), command.episode().requestKey()
        );
        if (existing != null) {
            return toDto(existing);
        }
        if (!agentRunService.transitionStatus(command.runId(), "RUNNING", "WAITING_APPROVAL")) {
            throw new BusinessException(40901, "运行状态已变化，无法创建审批请求");
        }
        LocalDateTime now = LocalDateTime.now();
        ApprovalRequestEntity entity = new ApprovalRequestEntity();
        entity.setApprovalId(UUID.randomUUID().toString());
        entity.setRunId(command.runId());
        entity.setUserId(command.userId());
        entity.setRootSessionId(command.rootSessionId());
        entity.setSessionId(command.sessionId());
        entity.setRequestKey(command.episode().requestKey());
        entity.setReplyId(command.episode().replyId());
        entity.setSubagentExecutionId(command.subagentExecutionId());
        entity.setApprovalMode(command.approvalMode().name());
        entity.setToolCallIdsJson(write(command.episode().toolCalls().stream()
                .map(call -> call.id()).toList()));
        entity.setToolNamesJson(write(command.episode().toolCalls().stream()
                .map(call -> call.name()).toList()));
        entity.setDisplayItemsJson(write(command.episode().toolCalls().stream()
                .map(call -> new ApprovalActionDto(call.id(), call.name(), call.displaySummary()))
                .toList()));
        entity.setStatus(ApprovalRequestStatus.PENDING.name());
        entity.setVersion(0);
        entity.setRequestedAt(now);
        entity.setUpdatedAt(now);
        approvalRequestMapper.insertApproval(entity);
        return toDto(entity);
    }

    @Override
    public ApprovalRequestDto findPending(Long userId, String sessionId) {
        ApprovalRequestEntity entity = approvalRequestMapper.selectPendingOwned(userId, sessionId);
        return entity == null ? null : toDto(entity);
    }

    @Override
    public ApprovalRequestDto getOwned(Long userId, String approvalId) {
        ApprovalRequestEntity entity = approvalRequestMapper.selectOwned(approvalId, userId);
        if (entity == null) {
            throw new BusinessException(40404, "审批请求不存在");
        }
        return toDto(entity);
    }

    @Override
    @Transactional
    public ApprovalResolution decide(Long userId, String approvalId, ApprovalDecision decision) {
        ApprovalRequestEntity entity = approvalRequestMapper.selectOwned(approvalId, userId);
        if (entity == null) {
            throw new BusinessException(40404, "审批请求不存在");
        }
        if (!ApprovalRequestStatus.PENDING.name().equals(entity.getStatus())) {
            throw new BusinessException(40901, "审批请求已处理，请刷新会话");
        }
        ApprovalRequestStatus nextStatus = decision == ApprovalDecision.APPROVE
                ? ApprovalRequestStatus.APPROVED : ApprovalRequestStatus.DENIED;
        int updated = approvalRequestMapper.decidePending(
                approvalId, userId, entity.getVersion(), nextStatus.name(), decision.name()
        );
        if (updated != 1) {
            throw new BusinessException(40901, "审批请求已被其他操作处理");
        }
        if (!agentRunService.transitionStatus(entity.getRunId(), "WAITING_APPROVAL", "RUNNING")) {
            throw new BusinessException(40901, "运行状态已变化，无法恢复执行");
        }
        entity.setStatus(nextStatus.name());
        entity.setDecision(decision.name());
        entity.setVersion(entity.getVersion() + 1);
        entity.setDecidedAt(LocalDateTime.now());
        return new ApprovalResolution(
                toDto(entity),
                read(entity.getToolCallIdsJson(), new TypeReference<List<String>>() { }),
                decision == ApprovalDecision.APPROVE
        );
    }

    private ApprovalRequestDto toDto(ApprovalRequestEntity entity) {
        return new ApprovalRequestDto(
                entity.getApprovalId(),
                entity.getRunId(),
                entity.getRootSessionId(),
                entity.getSessionId(),
                entity.getSubagentExecutionId(),
                ApprovalMode.valueOf(entity.getApprovalMode()),
                read(entity.getDisplayItemsJson(), new TypeReference<List<ApprovalActionDto>>() { }),
                ApprovalRequestStatus.valueOf(entity.getStatus()),
                entity.getDecision() == null ? null : ApprovalDecision.valueOf(entity.getDecision()),
                entity.getVersion(),
                entity.getRequestedAt(),
                entity.getDecidedAt()
        );
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException error) {
            throw new IllegalStateException("审批快照序列化失败", error);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException error) {
            throw new IllegalStateException("审批快照读取失败", error);
        }
    }
}
