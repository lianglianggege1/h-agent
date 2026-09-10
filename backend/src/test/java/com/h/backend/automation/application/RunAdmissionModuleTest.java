package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationRun;
import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.AutomationSchedule;
import com.h.backend.automation.domain.AutomationTask;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class RunAdmissionModuleTest {

    private static final Instant NOW = Instant.parse("2026-09-07T01:00:01Z");

    @Test
    void admitsOnlyOneRunForTheSameTaskRevisionAndLogicalFireTime() {
        FakeRepository repository = new FakeRepository(enabledTask());
        RunAdmissionModule module = new RunAdmissionModule(
                repository, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        Instant scheduledFor = Instant.parse("2026-09-07T01:00:00Z");

        RunAdmissionModule.AdmissionResult first = module.admitScheduled("task-1", 4, scheduledFor, "log-8");
        // Run 仍在执行时，重复触发先走重叠策略 SKIP。
        RunAdmissionModule.AdmissionResult overlapping = module.admitScheduled("task-1", 4, scheduledFor, "log-9");
        // Run 终结后（崩溃重放/重复投递），同一逻辑时刻由幂等键拒绝。
        repository.markAllTerminal();
        RunAdmissionModule.AdmissionResult duplicate = module.admitScheduled("task-1", 4, scheduledFor, "log-10");

        assertEquals(RunAdmissionModule.AdmissionStatus.ACCEPTED, first.status());
        assertNotNull(first.run());
        assertEquals("QUEUED", first.run().status());
        assertEquals(4, first.run().taskRevision());
        assertEquals(RunAdmissionModule.AdmissionStatus.SKIPPED_OVERLAP, overlapping.status());
        assertNull(overlapping.run());
        assertEquals(RunAdmissionModule.AdmissionStatus.SKIPPED_DUPLICATE, duplicate.status());
        assertNull(duplicate.run());
    }

    @Test
    void skipsTriggersOutsideMisfireWindow() {
        FakeRepository repository = new FakeRepository(enabledTask());
        RunAdmissionModule module = new RunAdmissionModule(
                repository, Clock.fixed(NOW, ZoneOffset.UTC)
        );
        // 观测时刻距离最近日程点超过 10 分钟 misfire 窗口。
        RunAdmissionModule.AdmissionResult result = module.admitScheduled(
                "task-1", 4, Instant.parse("2026-09-07T01:30:00Z"), "log-11");

        assertEquals(RunAdmissionModule.AdmissionStatus.SKIPPED_MISFIRE, result.status());
        assertNull(result.run());
    }

    @Test
    void mapsConcurrentActiveRunConstraintToOverlapInsteadOfDuplicate() {
        FakeRepository repository = new FakeRepository(enabledTask());
        repository.simulateConcurrentOverlap = true;
        RunAdmissionModule module = new RunAdmissionModule(
                repository, Clock.fixed(NOW, ZoneOffset.UTC)
        );

        RunAdmissionModule.AdmissionResult result = module.admitScheduled(
                "task-1", 4, Instant.parse("2026-09-07T01:00:00Z"), "log-race");

        assertEquals(RunAdmissionModule.AdmissionStatus.SKIPPED_OVERLAP, result.status());
        assertNull(result.run());
    }

    @Test
    void executionSnapshotCarriesOwnerAndTaskIdentity() throws Exception {
        RunAdmissionModule module = new RunAdmissionModule(
                new FakeRepository(enabledTask()), Clock.fixed(NOW, ZoneOffset.UTC)
        );

        String snapshot = module.snapshot(enabledTask(), "run-1", "SCHEDULED", NOW);
        var json = new ObjectMapper().readTree(snapshot);

        assertEquals(7L, json.path("userId").asLong());
        assertEquals("晨报", json.path("taskName").asText());
        assertEquals("session-1", json.path("sessionId").asText());
        assertEquals("NONE", json.path("deliverySink").asText());
    }

    private static AutomationTask enabledTask() {
        Instant createdAt = Instant.parse("2026-09-01T00:00:00Z");
        return new AutomationTask(
                "task-1", 7L, "晨报", "汇总今日动态", "standard-chat", AutomationRuntime.LANGCHAIN4J,
                new AutomationSchedule("0 0 9 * * *", "Asia/Shanghai"), true,
                Instant.parse("2026-09-07T01:00:00Z"), null, null, "UI", 4, createdAt, createdAt,
                "NONE", null, "session-1"
        );
    }

    private static final class FakeRepository implements AutomationTaskRepository {
        private final AutomationTask task;
        private final Map<String, AutomationRun> scheduledRuns = new HashMap<>();
        private boolean simulateConcurrentOverlap;
        private int activeChecks;

        private FakeRepository(AutomationTask task) {
            this.task = task;
        }

        @Override public AutomationTask insert(AutomationTask task) { throw new UnsupportedOperationException(); }
        @Override public AutomationTask updateOwned(Long userId, String taskId, long expectedRevision, AutomationTask replacement) { throw new UnsupportedOperationException(); }
        @Override public AutomationTask enableOwned(Long userId, String taskId, long expectedRevision, Instant nextRunAt, Instant updatedAt, int maxEnabled) { throw new UnsupportedOperationException(); }
        @Override public AutomationTask disableOwned(Long userId, String taskId, long expectedRevision, Instant updatedAt) { throw new UnsupportedOperationException(); }
        @Override public Optional<AutomationTask> findOwned(Long userId, String taskId) { return Optional.empty(); }
        @Override public Optional<AutomationTask> findById(String taskId) { return task.id().equals(taskId) ? Optional.of(task) : Optional.empty(); }
        @Override public List<AutomationTask> listOwned(Long userId) { return List.of(); }
        @Override public boolean softDeleteOwned(Long userId, String taskId) { return false; }
        @Override public void recordRunResult(String taskId, Instant at, String status) { }
        @Override public AutomationRun insertRun(AutomationRun run) { return run; }
        @Override public AutomationRun insertManualRunIfNoActive(AutomationRun run) { return run; }

        @Override
        public AutomationRun insertScheduledRunIfAbsent(AutomationRun run) {
            if (simulateConcurrentOverlap) {
                return null;
            }
            String key = run.taskId() + ":" + run.taskRevision() + ":" + run.scheduledFor();
            return scheduledRuns.putIfAbsent(key, run) == null ? run : null;
        }

        @Override public boolean existsActiveRun(String taskId) {
            if (simulateConcurrentOverlap && activeChecks++ > 0) {
                return true;
            }
            return scheduledRuns.values().stream()
                    .anyMatch(run -> run.taskId().equals(taskId)
                            && ("QUEUED".equals(run.status()) || "RUNNING".equals(run.status())));
        }
        void markAllTerminal() {
            scheduledRuns.replaceAll((key, run) -> new AutomationRun(
                    run.id(), run.taskId(), run.userId(), run.taskRevision(), run.triggerType(),
                    run.triggerId(), "SUCCEEDED", run.scheduledFor(), run.startedAt(), NOW,
                    run.sessionId(), run.output(), null, run.cancelRequestedAt(), run.specSnapshot()
            ));
        }
        @Override public Optional<AutomationRun> findRunOwned(Long userId, String runId) { return Optional.empty(); }
        @Override public Optional<AutomationRun> requestCancelRun(String runId, Instant now) { return Optional.empty(); }
        @Override public List<AutomationRun> listRunsOwned(Long userId, String taskId, int limit) { return List.of(); }
    }
}
