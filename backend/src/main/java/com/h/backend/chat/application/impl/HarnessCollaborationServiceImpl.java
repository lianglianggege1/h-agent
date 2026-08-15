package com.h.backend.chat.application.impl;

import com.h.backend.chat.application.HarnessCollaborationService;
import com.h.backend.chat.application.HarnessExecutionSession;
import com.h.backend.chat.application.HarnessSubagentCompletion;
import com.h.backend.chat.application.HarnessSubagentExposure;
import com.h.backend.chat.application.HarnessSubagentFailureReason;
import com.h.backend.chat.application.HarnessSubagentTurnStart;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.infrastructure.persistence.entity.AgentSessionEntity;
import com.h.backend.chat.infrastructure.persistence.entity.ChatSessionEntity;
import com.h.backend.chat.infrastructure.persistence.entity.ChatSessionMessageEntity;
import com.h.backend.chat.infrastructure.persistence.entity.HarnessSubagentEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.AgentSessionMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatSessionMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatSessionMessageMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.HarnessSubagentMapper;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceUseDto;
import com.h.backend.chat.interfaces.dto.HarnessSubagentStatus;
import com.h.backend.chat.interfaces.dto.HarnessSubagentSummaryDto;
import com.h.backend.common.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Harness 产品状态在统一 Agent Session 树上的实现。
 *
 * <p>父子拓扑、Gateway 句柄和消息计数属于 {@code agent_sessions}；本类只在
 * {@code harness_subagents} 保存协作者独有的展示信息与当前状态。</p>
 */
@Service
public class HarnessCollaborationServiceImpl implements HarnessCollaborationService {

    private static final int MAX_CHILDREN_PER_AGENT = 8;

    private record PendingAssignmentKey(Long userId, String sessionId) { }

    private final ChatSessionMapper chatSessionMapper;
    private final ChatSessionMessageMapper chatSessionMessageMapper;
    private final AgentSessionMapper agentSessionMapper;
    private final HarnessSubagentMapper harnessSubagentMapper;
    private final ChatMessageResourceBinder resourceBinder;
    private final ConcurrentMap<PendingAssignmentKey, String> pendingAssignments = new ConcurrentHashMap<>();

    public HarnessCollaborationServiceImpl(
            ChatSessionMapper chatSessionMapper,
            ChatSessionMessageMapper chatSessionMessageMapper,
            AgentSessionMapper agentSessionMapper,
            HarnessSubagentMapper harnessSubagentMapper,
            ChatMessageResourceBinder resourceBinder
    ) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatSessionMessageMapper = chatSessionMessageMapper;
        this.agentSessionMapper = agentSessionMapper;
        this.harnessSubagentMapper = harnessSubagentMapper;
        this.resourceBinder = resourceBinder;
    }

    @Override
    @Transactional(readOnly = true)
    public List<HarnessSubagentSummaryDto> listSubagents(Long userId, String parentSessionId) {
        requireOwnedHarnessRoot(userId, parentSessionId);
        return harnessSubagentMapper
                .selectDescendants(parentSessionId)
                .stream()
                .map(this::toSummary)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public HarnessExecutionSession resolveExecutionSession(Long userId, String sessionId) {
        AgentSessionEntity requested = agentSessionMapper.selectBySessionId(sessionId);
        if (requested == null || !userId.equals(requested.getUserId())) {
            throw new BusinessException(40404, "会话不存在");
        }
        AgentSessionEntity root = requested;
        int hops = 0;
        while (root.getParentSessionId() != null && hops++ < 64) {
            root = agentSessionMapper.selectBySessionId(root.getParentSessionId());
            if (root == null || !userId.equals(root.getUserId())) {
                throw new BusinessException(40404, "会话不存在");
            }
        }
        if (root.getParentSessionId() != null) {
            throw new BusinessException(40010, "Agent 会话层级无效");
        }
        requireOwnedHarnessRoot(userId, root.getSessionId());
        if (!root.getSessionId().equals(requested.getSessionId())) {
            if (requested.getGatewaySubagentId() == null
                    || harnessSubagentMapper.selectBySessionId(requested.getSessionId()) == null) {
                throw new BusinessException(40404, "协作 Agent 不存在");
            }
        }
        HarnessSubagentEntity subagent = root.getSessionId().equals(requested.getSessionId())
                ? null
                : harnessSubagentMapper.selectBySessionId(requested.getSessionId());
        return new HarnessExecutionSession(
                root.getSessionId(),
                requested.getSessionId(),
                requested.getGatewaySubagentId(),
                requested.getParentSessionId(),
                requested.getAgentId(),
                subagent == null ? null : subagent.getAssignment()
        );
    }

    @Override
    @Transactional
    public void projectSubagentResult(
            Long userId,
            String sessionId,
            String assignment,
            String reasoning,
            String content
    ) {
        if (userId == null || sessionId == null || sessionId.isBlank()
                || content == null || content.isBlank()) {
            return;
        }
        AgentSessionEntity session = agentSessionMapper.selectBySessionId(sessionId);
        if (session == null || session.getParentSessionId() == null || !userId.equals(session.getUserId())) {
            return;
        }
        HarnessSubagentEntity locked = harnessSubagentMapper.selectBySessionIdForUpdate(sessionId);
        AgentSessionEntity latestSession = agentSessionMapper.selectBySessionId(sessionId);
        ChatSessionMessageEntity latestMessage = chatSessionMessageMapper.selectLatestByAgentSessionId(sessionId);
        if (locked == null || latestSession == null
                || (latestMessage != null && "assistant".equals(latestMessage.getRoleCode()))) {
            return;
        }
        HarnessExecutionSession execution = resolveExecutionSession(userId, sessionId);
        ChatSessionEntity root = requireOwnedHarnessRoot(userId, execution.rootSessionId());
        boolean initialTurn = latestMessage == null || "system".equals(latestMessage.getRoleCode());
        if (initialTurn && assignment != null && !assignment.isBlank()
                && !assignment.equals(locked.getAssignment())) {
            locked.setAssignment(assignment.trim());
            locked.setUpdatedAt(LocalDateTime.now());
            harnessSubagentMapper.updateById(locked);
            updateAssignmentMessage(root.getId(), sessionId, assignment.trim());
        }
        if (latestSession.getMessageCount() == null || latestSession.getMessageCount() == 0) {
            ensureAssignmentMessage(root, latestSession, locked);
        }
        if (reasoning != null && !reasoning.isBlank()) {
            insertThreadMessage(root, sessionId, userId, "assistant", "REASONING", reasoning);
        }
        insertThreadMessage(root, sessionId, userId, "assistant", "AI", content.trim());
        locked.setStatus(HarnessSubagentStatus.COMPLETED.name());
        locked.setFailureReason(null);
        locked.setFailureMessage(null);
        locked.setFinishedAt(LocalDateTime.now());
        locked.setUpdatedAt(locked.getFinishedAt());
        harnessSubagentMapper.updateById(locked);
    }

    @Override
    @Transactional
    public void projectSubagentAssignment(Long userId, String sessionId, String assignment) {
        if (userId == null || sessionId == null || sessionId.isBlank()
                || assignment == null || assignment.isBlank()) {
            return;
        }
        AgentSessionEntity session = agentSessionMapper.selectBySessionId(sessionId);
        if (session == null) {
            // SDK 先启动子 Agent、父事件订阅稍后才消费 SUBAGENT_EXPOSED 时，先暂存实际输入；
            // exposure 在同一进程随后创建产品 Session 时会原子取走，避免回退成 label。
            pendingAssignments.put(new PendingAssignmentKey(userId, sessionId), assignment.trim());
            return;
        }
        if (session.getParentSessionId() == null || !userId.equals(session.getUserId())) {
            return;
        }
        HarnessSubagentEntity locked = harnessSubagentMapper.selectBySessionIdForUpdate(sessionId);
        AgentSessionEntity latestSession = agentSessionMapper.selectBySessionId(sessionId);
        ChatSessionMessageEntity latestMessage = chatSessionMessageMapper.selectLatestByAgentSessionId(sessionId);
        if (locked == null || latestSession == null
                || (latestMessage != null && !"system".equals(latestMessage.getRoleCode()))) {
            // 后续用户追加要求不能改写最初的父委托。
            return;
        }
        HarnessExecutionSession execution = resolveExecutionSession(userId, sessionId);
        ChatSessionEntity root = requireOwnedHarnessRoot(userId, execution.rootSessionId());
        String normalized = assignment.trim();
        if (!normalized.equals(locked.getAssignment())) {
            locked.setAssignment(normalized);
            locked.setUpdatedAt(LocalDateTime.now());
            harnessSubagentMapper.updateById(locked);
        }
        if (latestSession.getMessageCount() == null || latestSession.getMessageCount() == 0) {
            ensureAssignmentMessage(root, latestSession, locked);
        } else {
            updateAssignmentMessage(root.getId(), sessionId, normalized);
        }
    }

    private void updateAssignmentMessage(Long rootRecordId, String sessionId, String assignment) {
        List<ChatSessionMessageEntity> messages = chatSessionMessageMapper.selectBySessionRecordId(
                rootRecordId, sessionId
        );
        if (messages.isEmpty()) {
            return;
        }
        ChatSessionMessageEntity first = messages.getFirst();
        if (first.getSequenceNo() != null
                && first.getSequenceNo() == 1
                && "SYSTEM".equals(first.getMessageType())) {
            first.setContentText(assignment);
            chatSessionMessageMapper.updateById(first);
        }
    }

    @Override
    @Transactional
    public HarnessSubagentSummaryDto exposeSubagent(
            Long userId,
            String rootSessionId,
            HarnessSubagentExposure exposure
    ) {
        String pendingAssignment = pendingAssignments.remove(
                new PendingAssignmentKey(userId, exposure.sessionId())
        );
        String effectiveAssignment = pendingAssignment == null || pendingAssignment.isBlank()
                ? exposure.assignment()
                : pendingAssignment;
        requireOwnedHarnessRoot(userId, rootSessionId);
        AgentSessionEntity parent = agentSessionMapper.selectBySessionId(exposure.parentSessionId());
        if (parent == null) {
            throw new BusinessException(40404, "协作 Agent 的父会话不存在");
        }
        requireDescendantOrRoot(userId, rootSessionId, parent);

        AgentSessionEntity existing = agentSessionMapper.selectBySessionId(exposure.sessionId());
        if (existing != null) {
            requireDescendantOfRoot(userId, rootSessionId, existing);
            HarnessSubagentEntity product = harnessSubagentMapper.selectBySessionId(existing.getSessionId());
            if (product != null) {
                ensureAssignmentMessage(requireOwnedHarnessRoot(userId, rootSessionId), existing, product);
                return toSummary(product);
            }
            // 兼容迁移可先从历史消息推导出没有 Gateway 句柄的通用子 Session；
            // 首次 exposure 将它提升为用户可寻址协作者，同时保留原消息和拓扑。
            existing.setGatewaySubagentId(exposure.gatewaySubagentId());
            existing.setAgentId(exposure.agentId());
            existing.setUpdatedAt(LocalDateTime.now());
            agentSessionMapper.updateById(existing);
            HarnessSubagentEntity promoted = new HarnessSubagentEntity();
            promoted.setSessionId(existing.getSessionId());
            promoted.setDisplayName(exposure.displayName());
            promoted.setAssignment(effectiveAssignment);
            promoted.setStatus(HarnessSubagentStatus.AVAILABLE.name());
            promoted.setCreatedAt(existing.getUpdatedAt());
            promoted.setUpdatedAt(existing.getUpdatedAt());
            harnessSubagentMapper.insert(promoted);
            ensureAssignmentMessage(requireOwnedHarnessRoot(userId, rootSessionId), existing, promoted);
            return toSummary(promoted);
        }
        List<AgentSessionEntity> siblings = agentSessionMapper.selectChildren(parent.getSessionId());
        if (siblings.size() >= MAX_CHILDREN_PER_AGENT) {
            throw new BusinessException(40009, "单个 Agent 最多支持 8 个直接协作者");
        }

        LocalDateTime now = LocalDateTime.now();
        AgentSessionEntity session = new AgentSessionEntity();
        session.setSessionId(exposure.sessionId());
        session.setParentSessionId(parent.getSessionId());
        session.setUserId(userId);
        session.setAgentId(exposure.agentId());
        session.setGatewaySubagentId(exposure.gatewaySubagentId());
        session.setDisplayOrder(siblings.size());
        session.setMessageCount(0);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        agentSessionMapper.insert(session);

        HarnessSubagentEntity product = new HarnessSubagentEntity();
        product.setSessionId(session.getSessionId());
        product.setDisplayName(exposure.displayName());
        product.setAssignment(effectiveAssignment);
        product.setStatus(HarnessSubagentStatus.AVAILABLE.name());
        product.setCreatedAt(now);
        product.setUpdatedAt(now);
        harnessSubagentMapper.insert(product);
        ensureAssignmentMessage(requireOwnedHarnessRoot(userId, rootSessionId), session, product);
        return toSummary(product);
    }

    /** 委托是子会话的第一条标准 SYSTEM 消息；重复 exposure 不得重复插入。 */
    private void ensureAssignmentMessage(
            ChatSessionEntity root,
            AgentSessionEntity session,
            HarnessSubagentEntity product
    ) {
        if (session.getMessageCount() != null && session.getMessageCount() > 0) {
            return;
        }
        insertThreadMessage(
                root,
                session.getSessionId(),
                session.getUserId(),
                "system",
                "SYSTEM",
                product.getAssignment()
        );
    }

    @Override
    @Transactional
    public HarnessSubagentSummaryDto markRunning(
            Long userId, String rootSessionId, String sessionId, String executionId
    ) {
        AgentSessionEntity session = requireProductSession(userId, rootSessionId, sessionId, false);
        if (session == null) {
            // AgentStart 可能先于 exposure 投影到达；未登记的内部节点不产生产品状态。
            return null;
        }
        HarnessSubagentEntity entity = harnessSubagentMapper.selectBySessionId(session.getSessionId());
        if (entity == null) {
            return null;
        }
        if (HarnessSubagentStatus.RUNNING.name().equals(entity.getStatus())) {
            return toSummary(entity);
        }
        if (executionId == null || executionId.isBlank()) {
            return toSummary(entity);
        }
        harnessSubagentMapper.startExecution(sessionId, executionId);
        return toSummary(harnessSubagentMapper.selectBySessionId(sessionId));
    }

    @Override
    @Transactional
    public HarnessSubagentCompletion completeSubagent(
            Long userId,
            String rootSessionId,
            String sessionId,
            String executionId,
            String content
    ) {
        ChatSessionEntity root = requireOwnedHarnessRoot(userId, rootSessionId);
        requireProductSession(userId, rootSessionId, sessionId, true);
        HarnessSubagentEntity entity = harnessSubagentMapper.selectBySessionId(sessionId);
        if (entity == null || executionId == null || executionId.isBlank()) {
            throw new BusinessException(40404, "协作 Agent 不存在");
        }
        int transitioned = harnessSubagentMapper.completeExecution(sessionId, executionId);
        if (transitioned == 0) {
            // 重复或迟到终态属于正常的事件投影竞争；保留当前较新的状态，不制造重复消息。
            HarnessSubagentEntity latest = harnessSubagentMapper.selectBySessionId(sessionId);
            Long existingAssistantId = latest != null
                    && HarnessSubagentStatus.COMPLETED.name().equals(latest.getStatus())
                    ? chatSessionMessageMapper.selectLatestAssistantMessageId(sessionId)
                    : null;
            return new HarnessSubagentCompletion(existingAssistantId, toSummary(latest));
        }
        Long messageId = insertThreadMessage(root, sessionId, userId, "assistant", "AI", content);
        return new HarnessSubagentCompletion(
                messageId,
                toSummary(harnessSubagentMapper.selectBySessionId(sessionId))
        );
    }

    @Override
    @Transactional
    public HarnessSubagentTurnStart beginSubagentTurn(
            Long userId,
            String rootSessionId,
            String sessionId,
            String content,
            List<ChatMessageResourceUseDto> resources
    ) {
        ChatSessionEntity root = requireOwnedHarnessRoot(userId, rootSessionId);
        requireProductSession(userId, rootSessionId, sessionId, true);
        HarnessSubagentEntity entity = harnessSubagentMapper.selectBySessionId(sessionId);
        HarnessSubagentStatus status = HarnessSubagentStatus.valueOf(entity.getStatus());
        if (status != HarnessSubagentStatus.AVAILABLE
                && status != HarnessSubagentStatus.COMPLETED
                && status != HarnessSubagentStatus.FAILED) {
            throw new BusinessException(40010, "当前协作 Agent 正在处理中");
        }
        if (content == null || content.isBlank()) {
            throw new BusinessException(40000, "追加要求不能为空");
        }
        String executionId = UUID.randomUUID().toString();
        if (harnessSubagentMapper.startExecution(sessionId, executionId) == 0) {
            throw new BusinessException(40010, "当前协作 Agent 正在处理中");
        }
        Long messageId = insertThreadMessage(root, sessionId, userId, "user", "USER", content.trim());
        resourceBinder.bind(userId, messageId, resources);
        return new HarnessSubagentTurnStart(
                messageId,
                executionId,
                toSummary(harnessSubagentMapper.selectBySessionId(sessionId))
        );
    }

    @Override
    @Transactional
    public HarnessSubagentSummaryDto failSubagent(
            Long userId,
            String rootSessionId,
            String sessionId,
            String executionId,
            HarnessSubagentFailureReason reason,
            String message
    ) {
        requireProductSession(userId, rootSessionId, sessionId, true);
        HarnessSubagentEntity entity = harnessSubagentMapper.selectBySessionId(sessionId);
        if (executionId == null || executionId.isBlank()) {
            return toSummary(entity);
        }
        harnessSubagentMapper.failExecution(
                sessionId,
                executionId,
                reason == null ? HarnessSubagentFailureReason.EXECUTION_ERROR.name() : reason.name(),
                message
        );
        return toSummary(harnessSubagentMapper.selectBySessionId(sessionId));
    }

    private Long insertThreadMessage(
            ChatSessionEntity root,
            String agentSessionId,
            Long userId,
            String role,
            String messageType,
            String content
    ) {
        Integer sequence = agentSessionMapper.nextMessageSequence(agentSessionId);
        if (sequence == null) {
            throw new IllegalStateException("Failed to allocate chat message sequence");
        }
        LocalDateTime now = LocalDateTime.now();
        ChatSessionMessageEntity message = new ChatSessionMessageEntity();
        message.setSessionRecordId(root.getId());
        message.setSessionId(agentSessionId);
        message.setUserId(userId);
        message.setSequenceNo(sequence);
        message.setMessageType(messageType);
        message.setRoleCode(role);
        message.setContentText(content);
        message.setPayloadJson("{}");
        message.setCreatedAt(now);
        chatSessionMessageMapper.insert(message);
        root.setLastActiveAt(now);
        root.setUpdatedAt(now);
        chatSessionMapper.touch(root.getId(), now);
        return message.getId();
    }

    private ChatSessionEntity requireOwnedHarnessRoot(Long userId, String rootSessionId) {
        ChatSessionEntity root = chatSessionMapper.selectBySessionId(rootSessionId);
        if (root == null || !userId.equals(root.getUserId())) {
            throw new BusinessException(40404, "会话不存在");
        }
        if (!ChatAgentIds.HARNESS.equals(root.getAgentId())) {
            throw new BusinessException(40008, "会话不是协作 Agent 会话");
        }
        return root;
    }

    private AgentSessionEntity requireProductSession(
            Long userId,
            String rootSessionId,
            String sessionId,
            boolean required
    ) {
        requireOwnedHarnessRoot(userId, rootSessionId);
        AgentSessionEntity session = agentSessionMapper.selectBySessionId(sessionId);
        if (session == null) {
            if (!required) return null;
            throw new BusinessException(40404, "协作 Agent 不存在");
        }
        requireDescendantOfRoot(userId, rootSessionId, session);
        if (harnessSubagentMapper.selectBySessionId(sessionId) == null) {
            if (!required) return null;
            throw new BusinessException(40404, "协作 Agent 不存在");
        }
        return session;
    }

    private void requireDescendantOrRoot(Long userId, String rootSessionId, AgentSessionEntity session) {
        if (rootSessionId.equals(session.getSessionId())) {
            if (!userId.equals(session.getUserId())) {
                throw new BusinessException(40404, "会话不存在");
            }
            return;
        }
        requireDescendantOfRoot(userId, rootSessionId, session);
    }

    /** 沿直接父链校验授权；避免仅凭全局 Gateway 句柄跨顶级会话访问。 */
    private void requireDescendantOfRoot(Long userId, String rootSessionId, AgentSessionEntity start) {
        AgentSessionEntity current = start;
        int hops = 0;
        while (current != null && hops++ < 64) {
            if (!userId.equals(current.getUserId())) {
                break;
            }
            if (rootSessionId.equals(current.getParentSessionId())) {
                return;
            }
            current = current.getParentSessionId() == null
                    ? null
                    : agentSessionMapper.selectBySessionId(current.getParentSessionId());
        }
        throw new BusinessException(40404, "协作 Agent 不属于当前会话");
    }

    private HarnessSubagentSummaryDto toSummary(HarnessSubagentEntity entity) {
        if (entity == null) {
            throw new BusinessException(40404, "协作 Agent 不存在");
        }
        AgentSessionEntity session = agentSessionMapper.selectBySessionId(entity.getSessionId());
        return new HarnessSubagentSummaryDto(
                session.getSessionId(),
                session.getParentSessionId(),
                entity.getDisplayName(),
                entity.getAssignment(),
                HarnessSubagentStatus.valueOf(entity.getStatus()),
                session.getDisplayOrder(),
                entity.getUpdatedAt()
        );
    }

}
