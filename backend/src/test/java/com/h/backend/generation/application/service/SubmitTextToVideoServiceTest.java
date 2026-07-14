package com.h.backend.generation.application.service;

import com.h.backend.generation.application.command.SubmitTextToVideoCommand;
import com.h.backend.generation.application.port.out.GenerationChatProjectionPort;
import com.h.backend.generation.application.port.out.TextToVideoSubmissionPort;
import com.h.backend.generation.domain.model.GenerationStatus;
import com.h.backend.generation.domain.model.GenerationTask;
import com.h.backend.generation.application.port.out.GenerationTaskRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubmitTextToVideoServiceTest {
    @Test
    void submitsProviderTaskAndCreatesOnePendingChatMessage() {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        RecordingProjection projection = new RecordingProjection();
        SubmitTextToVideoService service = new SubmitTextToVideoService(
                repository,
                spec -> "minimax-task-1",
                projection,
                Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC)
        );

        var result = service.execute(new SubmitTextToVideoCommand(
                1L, "session-1", "原始提示词", "最终提示词", null, null, null,
                false, false, false
        ));

        GenerationTask task = repository.findById(result.taskId()).orElseThrow();
        assertEquals("minimax-task-1", result.providerTaskId());
        assertEquals(101L, result.chatMessageId());
        assertEquals(GenerationStatus.IN_PROGRESS, task.status());
        assertEquals(101L, task.chatMessageId());
        assertEquals(Instant.parse("2026-07-14T00:00:05Z"), task.nextPollAt());
        assertEquals(1, projection.created.size());
    }

    private static final class RecordingProjection implements GenerationChatProjectionPort {
        private final List<String> created = new ArrayList<>();

        @Override
        public Long createPendingMessage(GenerationTask task) {
            created.add(task.id());
            return 101L;
        }

        @Override
        public void updateMessage(GenerationTask task) {
        }
    }

    private static final class InMemoryTaskRepository implements GenerationTaskRepository {
        private final List<GenerationTask> tasks = new ArrayList<>();

        @Override
        public void save(GenerationTask task) {
            tasks.removeIf(existing -> existing.id().equals(task.id()));
            tasks.add(task);
        }

        @Override
        public Optional<GenerationTask> findById(String taskId) {
            return tasks.stream().filter(task -> task.id().equals(taskId)).findFirst();
        }

        @Override
        public List<GenerationTask> findDue(Instant now, int limit) {
            return List.of();
        }
    }
}
