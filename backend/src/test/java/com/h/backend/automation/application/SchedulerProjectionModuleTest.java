package com.h.backend.automation.application;

import com.h.backend.automation.application.SchedulerProjectionGateway.DesiredState;
import com.h.backend.automation.application.SchedulerProjectionGateway.ProjectionCommand;
import com.h.backend.automation.application.SchedulerProjectionGateway.ProjectionResult;
import com.h.backend.automation.infrastructure.execution.AutomationProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchedulerProjectionModuleTest {

    @Test
    void convergesOnlyCurrentOutboxEventAndPersistsRemoteJobId() {
        Instant now = Instant.parse("2026-09-07T09:00:00Z");
        FakeRepository repository = new FakeRepository(List.of(
                new SchedulerProjectionWork("old", "task-1", 1, DesiredState.ACTIVE,
                        null, "晨报", "0 0 9 * * *", "Asia/Shanghai", 0),
                new SchedulerProjectionWork("current", "task-1", 2, DesiredState.ACTIVE,
                        null, "晨报", "0 30 9 * * *", "Asia/Shanghai", 0)
        ));
        repository.currentRevision = 2;
        RecordingGateway gateway = new RecordingGateway();
        SchedulerProjectionModule module = new SchedulerProjectionModule(
                repository, gateway, properties(), Clock.fixed(now, ZoneOffset.UTC)
        );

        module.synchronizePending();

        assertEquals(List.of("old"), repository.discarded);
        assertEquals(List.of("current:42"), repository.completed);
        assertEquals(List.of(2L), gateway.revisions);
    }

    @Test
    void acquiresTaskFenceBeforeCallingRemoteScheduler() {
        SchedulerProjectionWork work = new SchedulerProjectionWork(
                "current", "task-1", 2, DesiredState.ACTIVE,
                null, "晨报", "0 0 9 * * *", "Asia/Shanghai", 0
        );
        FakeRepository repository = new FakeRepository(List.of(work));
        repository.currentRevision = 2;
        SchedulerProjectionGateway gateway = command -> {
            assertTrue(repository.taskLocked);
            return new ProjectionResult(42L);
        };
        SchedulerProjectionModule module = new SchedulerProjectionModule(
                repository, gateway, properties(), Clock.fixed(
                Instant.parse("2026-09-07T09:00:00Z"), ZoneOffset.UTC)
        );

        module.synchronizePending();

        assertEquals(List.of("current:42"), repository.completed);
    }

    private static AutomationProperties properties() {
        AutomationProperties properties = new AutomationProperties();
        properties.getXxlJob().setBatchSize(10);
        properties.getXxlJob().setLeaseDuration(Duration.ofSeconds(30));
        return properties;
    }

    private static final class RecordingGateway implements SchedulerProjectionGateway {
        private final List<Long> revisions = new ArrayList<>();

        @Override
        public ProjectionResult converge(ProjectionCommand command) {
            revisions.add(command.taskRevision());
            return new ProjectionResult(42L);
        }
    }

    private static final class FakeRepository implements SchedulerProjectionRepository {
        private final List<SchedulerProjectionWork> work;
        private final List<String> discarded = new ArrayList<>();
        private final List<String> completed = new ArrayList<>();
        private long currentRevision;
        private boolean taskLocked;

        private FakeRepository(List<SchedulerProjectionWork> work) {
            this.work = work;
        }

        @Override
        public List<SchedulerProjectionWork> claim(
                Instant now, int limit, String leaseOwner, Duration leaseDuration
        ) {
            return work;
        }

        @Override
        public boolean isCurrent(SchedulerProjectionWork item) {
            return item.taskRevision() == currentRevision;
        }

        @Override
        public void lockTask(String taskId) {
            taskLocked = true;
        }

        @Override
        public void complete(SchedulerProjectionWork item, Long jobId, Instant now) {
            completed.add(item.outboxId() + ":" + jobId);
        }

        @Override
        public void discard(SchedulerProjectionWork item, Instant now) {
            discarded.add(item.outboxId());
        }

        @Override
        public void retry(SchedulerProjectionWork item, String errorMessage, Instant availableAt, Instant now) {
            throw new AssertionError("unexpected retry");
        }
    }
}
