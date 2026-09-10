package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationProposal;
import com.h.backend.automation.domain.AutomationProposalAction;
import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.AutomationSchedule;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.domain.model.AgentRunSummary;
import com.h.backend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AutomationProposalModuleTest {

    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void concurrentConfirmationAppliesCreateOnlyOnce() throws Exception {
        LockingProposalRepository proposals = new LockingProposalRepository(pendingCreate());
        RecordingTaskService tasks = new RecordingTaskService();
        AutomationProposalModule module = new AutomationProposalModule(
                proposals, tasks, AutomationAuditRecorder.NOOP, null, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> module.confirm(7L, "proposal-1"));
            var second = executor.submit(() -> module.confirm(7L, "proposal-1"));
            first.get(2, TimeUnit.SECONDS);
            second.get(2, TimeUnit.SECONDS);
        }

        assertEquals(1, tasks.createCount.get());
    }

    @Test
    void createChangeProposalBindsUniqueOpenRun() {
        CapturingProposalRepository proposals = new CapturingProposalRepository();
        StubTaskService tasks = new StubTaskService();
        AgentRunService agentRunService = mock(AgentRunService.class);
        when(agentRunService.requireOpenRun(7L, "session-1"))
                .thenReturn(new AgentRunSummary(51L, "RUNNING", null, 0, "[]", null, null));
        AutomationProposalModule module = new AutomationProposalModule(
                proposals, tasks, AutomationAuditRecorder.NOOP, agentRunService, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        AutomationProposal created = module.createChangeProposal(
                7L, AutomationProposalAction.CREATE, null, createCommand(),
                "session-1", "CHAT_LANGCHAIN4J"
        );

        assertEquals("PENDING", created.status());
        assertEquals(51L, created.sourceAgentRunId());
        assertEquals(51L, proposals.inserted.get().sourceAgentRunId());
    }

    @Test
    void createChangeProposalRejectsWhenNoOpenRun() {
        CapturingProposalRepository proposals = new CapturingProposalRepository();
        StubTaskService tasks = new StubTaskService();
        AgentRunService agentRunService = mock(AgentRunService.class);
        when(agentRunService.requireOpenRun(7L, "session-1"))
                .thenThrow(new BusinessException(40940, "当前会话无开放 AgentRun"));
        AutomationProposalModule module = new AutomationProposalModule(
                proposals, tasks, AutomationAuditRecorder.NOOP, agentRunService, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        BusinessException error = assertThrows(BusinessException.class, () -> module.createChangeProposal(
                7L, AutomationProposalAction.CREATE, null, createCommand(),
                "session-1", "CHAT_LANGCHAIN4J"
        ));

        assertEquals(40940, error.getCode());
        assertNull(proposals.inserted.get());
    }

    @Test
    void confirmPreservesSourceAgentRunIdAnchor() {
        LockingProposalRepository proposals = new LockingProposalRepository(pendingCreateWithRun(51L));
        RecordingTaskService tasks = new RecordingTaskService();
        AutomationProposalModule module = new AutomationProposalModule(
                proposals, tasks, AutomationAuditRecorder.NOOP, null, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        AutomationProposal confirmed = module.confirm(7L, "proposal-1");

        assertEquals("CONFIRMED", confirmed.status());
        assertEquals(51L, confirmed.sourceAgentRunId());
    }

    private static AutomationTaskCommand createCommand() {
        return new AutomationTaskCommand(
                "晨报", "汇总今天的行业动态", "standard-chat", null,
                "0 0 9 * * *", "Asia/Shanghai", false, "SESSION", "session-1"
        );
    }

    private static AutomationProposal pendingCreateWithRun(Long sourceAgentRunId) {
        return new AutomationProposal(
                "proposal-1", 7L, null, "CREATE",
                "{\"name\":\"晨报\",\"instruction\":\"汇总今天的行业动态\",\"agentId\":\"standard-chat\","
                        + "\"runtime\":\"LANGCHAIN4J\",\"cronExpression\":\"0 0 9 * * *\","
                        + "\"zoneId\":\"Asia/Shanghai\",\"deliverySink\":\"NONE\"}",
                null, "PENDING", "session-1", "CHAT", "request-1", null, sourceAgentRunId,
                NOW.plusSeconds(3600), null, null, NOW, NOW
        );
    }

    private static AutomationProposal pendingCreate() {
        return new AutomationProposal(
                "proposal-1", 7L, null, "CREATE",
                "{\"name\":\"晨报\",\"instruction\":\"汇总今天的行业动态\",\"agentId\":\"standard-chat\","
                        + "\"runtime\":\"LANGCHAIN4J\",\"cronExpression\":\"0 0 9 * * *\","
                        + "\"zoneId\":\"Asia/Shanghai\",\"deliverySink\":\"NONE\"}",
                null, "PENDING", "session-1", "CHAT", "request-1", null, null,
                NOW.plusSeconds(3600), null, null, NOW, NOW
        );
    }

    private static final class RecordingTaskService extends AutomationTaskService {
        private final AtomicInteger createCount = new AtomicInteger();
        private final AtomicReference<AutomationTask> createdTask = new AtomicReference<>();

        private RecordingTaskService() {
            super(null, null);
        }

        @Override
        public AutomationTask create(Long userId, AutomationTaskCommand command, String createdVia,
                                     String sourceSessionId) {
            int number = createCount.incrementAndGet();
            AutomationTask task = new AutomationTask(
                    "task-" + number, userId, command.name(), command.instruction(), command.agentId(),
                    command.runtime(), new com.h.backend.automation.domain.AutomationSchedule(
                    command.cronExpression(), command.zoneId()), false, null, null, null,
                    createdVia, 1L, NOW, NOW
            );
            createdTask.set(task);
            return task;
        }

        @Override
        public AutomationTask enable(Long userId, String taskId, long expectedRevision) {
            AutomationTask task = createdTask.get();
            return new AutomationTask(
                    task.id(), task.userId(), task.name(), task.instruction(), task.agentId(), task.runtime(),
                    task.schedule(), true, NOW, task.lastRunAt(), task.lastStatus(), task.createdVia(),
                    task.revision() + 1, task.createdAt(), NOW,
                    task.deliverySink(), task.deliverySessionId(), task.sessionId()
            );
        }
    }

    private static final class LockingProposalRepository implements AutomationProposalRepository {
        private final AtomicReference<AutomationProposal> value;
        private final CountDownLatch unlockedReaders = new CountDownLatch(2);
        private final ReentrantLock confirmationLock = new ReentrantLock();

        private LockingProposalRepository(AutomationProposal proposal) {
            value = new AtomicReference<>(proposal);
        }

        @Override public AutomationProposal insert(AutomationProposal proposal) { value.set(proposal); return proposal; }

        @Override
        public Optional<AutomationProposal> findOwned(Long userId, String proposalId) {
            AutomationProposal snapshot = value.get();
            unlockedReaders.countDown();
            try { unlockedReaders.await(1, TimeUnit.SECONDS); } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            return Optional.of(snapshot);
        }

        @Override
        public Optional<AutomationProposal> findOwnedForUpdate(Long userId, String proposalId) {
            try {
                if (!confirmationLock.tryLock(1, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("提案测试锁未释放");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("提案测试等待被中断", interrupted);
            }
            return Optional.of(value.get());
        }

        @Override public List<AutomationProposal> listPendingOwned(Long userId, Instant now) { return List.of(); }

        @Override
        public AutomationProposal markConfirmed(String proposalId, String resultTaskId, Long confirmedBy, Instant now) {
            AutomationProposal current = value.get();
            AutomationProposal confirmed = new AutomationProposal(
                    current.id(), current.userId(), current.taskId(), current.action(), current.payloadJson(),
                    current.baseTaskRevision(), "CONFIRMED", current.sourceSessionId(), current.createdVia(),
                    current.idempotencyKey(), resultTaskId, current.sourceAgentRunId(), current.expiresAt(),
                    now, confirmedBy, current.createdAt(), now
            );
            value.set(confirmed);
            if (confirmationLock.isHeldByCurrentThread()) confirmationLock.unlock();
            return confirmed;
        }

        @Override public AutomationProposal markDiscarded(Long userId, String proposalId, Instant now) { return null; }
        @Override public int markExpired(Instant now) { return 0; }
    }

    private static final class StubTaskService extends AutomationTaskService {
        private StubTaskService() {
            super(null, null);
        }

        @Override
        public ValidatedCommand validateCommandForSession(
                Long userId, String sessionId, AutomationTaskCommand command) {
            return new ValidatedCommand(
                    command.name(), command.instruction(), command.agentId(),
                    AutomationRuntime.LANGCHAIN4J,
                    new AutomationSchedule(command.cronExpression(), command.zoneId()),
                    command.deliverySink(), command.deliverySessionId()
            );
        }
    }

    private static final class CapturingProposalRepository implements AutomationProposalRepository {
        private final AtomicReference<AutomationProposal> inserted = new AtomicReference<>();

        @Override public AutomationProposal insert(AutomationProposal proposal) {
            inserted.set(proposal);
            return proposal;
        }

        @Override public Optional<AutomationProposal> findOwned(Long userId, String proposalId) {
            return Optional.ofNullable(inserted.get());
        }

        @Override public List<AutomationProposal> listPendingOwned(Long userId, Instant now) {
            return List.of();
        }

        @Override public AutomationProposal markConfirmed(
                String proposalId, String resultTaskId, Long confirmedBy, Instant now) {
            return inserted.get();
        }

        @Override public AutomationProposal markDiscarded(Long userId, String proposalId, Instant now) {
            return inserted.get();
        }

        @Override public int markExpired(Instant now) {
            return 0;
        }
    }
}
