package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationProposal;
import com.h.backend.automation.domain.AutomationTask;
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

class AutomationProposalModuleTest {

    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void concurrentConfirmationAppliesCreateOnlyOnce() throws Exception {
        LockingProposalRepository proposals = new LockingProposalRepository(pendingCreate());
        RecordingTaskService tasks = new RecordingTaskService();
        AutomationProposalModule module = new AutomationProposalModule(
                proposals, tasks, AutomationAuditRecorder.NOOP, new ObjectMapper(),
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

    private static AutomationProposal pendingCreate() {
        return new AutomationProposal(
                "proposal-1", 7L, null, "CREATE",
                "{\"name\":\"晨报\",\"instruction\":\"汇总今天的行业动态\",\"agentId\":\"standard-chat\","
                        + "\"runtime\":\"LANGCHAIN4J\",\"cronExpression\":\"0 0 9 * * *\","
                        + "\"zoneId\":\"Asia/Shanghai\",\"deliverySink\":\"NONE\"}",
                null, "PENDING", null, "CHAT", "request-1", null,
                NOW.plusSeconds(3600), null, null, NOW, NOW
        );
    }

    private static final class RecordingTaskService extends AutomationTaskService {
        private final AtomicInteger createCount = new AtomicInteger();

        private RecordingTaskService() {
            super(null, null);
        }

        @Override
        public AutomationTask create(Long userId, AutomationTaskCommand command, String createdVia) {
            int number = createCount.incrementAndGet();
            return new AutomationTask(
                    "task-" + number, userId, command.name(), command.instruction(), command.agentId(),
                    command.runtime(), new com.h.backend.automation.domain.AutomationSchedule(
                    command.cronExpression(), command.zoneId()), false, null, null, null,
                    createdVia, 1L, NOW, NOW
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
            confirmationLock.lock();
            return Optional.of(value.get());
        }

        @Override public List<AutomationProposal> listPendingOwned(Long userId, Instant now) { return List.of(); }

        @Override
        public AutomationProposal markConfirmed(String proposalId, String resultTaskId, Long confirmedBy, Instant now) {
            AutomationProposal current = value.get();
            AutomationProposal confirmed = new AutomationProposal(
                    current.id(), current.userId(), current.taskId(), current.action(), current.payloadJson(),
                    current.baseTaskRevision(), "CONFIRMED", current.sourceSessionId(), current.createdVia(),
                    current.idempotencyKey(), resultTaskId, current.expiresAt(), now, confirmedBy,
                    current.createdAt(), now
            );
            value.set(confirmed);
            if (confirmationLock.isHeldByCurrentThread()) confirmationLock.unlock();
            return confirmed;
        }

        @Override public AutomationProposal markDiscarded(Long userId, String proposalId, Instant now) { return null; }
        @Override public int markExpired(Instant now) { return 0; }
    }
}
