package com.h.backend.generation.application.service;

import com.h.backend.generation.application.port.out.GenerationChatProjectionPort;
import com.h.backend.generation.application.port.out.GenerationTaskRepository;
import com.h.backend.generation.application.port.out.ProviderTaskQueryPort;
import com.h.backend.generation.application.port.out.ProviderTaskRejectedException;
import com.h.backend.generation.domain.model.GenerationStatus;
import com.h.backend.generation.domain.model.GenerationTask;
import com.h.backend.generation.domain.model.TextToVideoSpec;
import com.h.backend.generation.infrastructure.config.GenerationProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PollDueGenerationTasksServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-07T16:07:11Z");

    @Test
    void permanentlyRejectedProviderTaskIsFailedWithoutRetrying() {
        GenerationTask task = GenerationTask.create(
                "task-1",
                1L,
                "session-1",
                TextToVideoSpec.withDefaults("原始提示词", "最终提示词", "MiniMax-Hailuo-2.3", null, null, false, false, false),
                NOW.minusSeconds(60)
        );
        task.markSubmitted("provider-task-1", NOW.minusSeconds(30), NOW.minusSeconds(60));
        InMemoryTaskRepository repository = new InMemoryTaskRepository(task);
        RecordingProjection projection = new RecordingProjection();
        ProviderTaskRejectedException rejection = new ProviderTaskRejectedException(
                1026,
                "MiniMax error 1026: input new_sensitive, input first_frame_image sensitive"
        );
        ProviderTaskQueryPort queryPort = providerTaskId -> {
            assertEquals("provider-task-1", providerTaskId);
            throw rejection;
        };

        PollDueGenerationTasksService service = new PollDueGenerationTasksService(
                repository,
                queryPort,
                null,
                projection,
                new GenerationProperties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );

        service.execute();

        assertEquals(GenerationStatus.FAILED, task.status());
        assertEquals(0, task.retryCount());
        assertEquals(rejection.getMessage(), task.failureMessage());
        assertEquals(1, repository.saveCount);
        assertEquals(1, projection.updated.size());
        assertTrue(task.nextPollAt() == null);
    }

    private static final class InMemoryTaskRepository implements GenerationTaskRepository {
        private final GenerationTask task;
        private int saveCount;

        private InMemoryTaskRepository(GenerationTask task) {
            this.task = task;
        }

        @Override
        public void save(GenerationTask task) {
            saveCount++;
        }

        @Override
        public Optional<GenerationTask> findById(String taskId) {
            return Optional.of(task);
        }

        @Override
        public List<GenerationTask> findDue(Instant now, int limit) {
            return List.of(task);
        }
    }

    private static final class RecordingProjection implements GenerationChatProjectionPort {
        private final List<GenerationTask> updated = new ArrayList<>();

        @Override
        public Long createPendingMessage(GenerationTask task) {
            return 1L;
        }

        @Override
        public void updateMessage(GenerationTask task) {
            updated.add(task);
        }
    }
}
