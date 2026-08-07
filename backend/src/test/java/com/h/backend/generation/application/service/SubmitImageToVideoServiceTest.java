package com.h.backend.generation.application.service;

import com.h.backend.chat.application.reference.ReferenceImageResolver;
import com.h.backend.chat.application.reference.ResolvedReferenceImage;
import com.h.backend.generation.application.command.SubmitImageToVideoCommand;
import com.h.backend.generation.application.port.out.GenerationChatProjectionPort;
import com.h.backend.generation.application.port.out.GenerationTaskRepository;
import com.h.backend.generation.application.port.out.ImageToVideoSubmissionPort;
import com.h.backend.generation.domain.model.GenerationTask;
import com.h.backend.generation.domain.model.GenerationType;
import com.h.backend.generation.domain.service.ImageToVideoSourceValidator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SubmitImageToVideoServiceTest {
    @Test
    void submitsAnOwnedReferenceImageAsAnImageToVideoTask() {
        InMemoryTaskRepository repository = new InMemoryTaskRepository();
        ResolvedReferenceImage image = new ResolvedReferenceImage("image-1", "image/png", new byte[]{1}, 1L, 512, 512);
        ReferenceImageResolver resolver = (userId, resourceId) -> image;
        ImageToVideoSubmissionPort submissionPort = (spec, source) -> {
            assertEquals("image-1", spec.sourceResourceId());
            assertEquals(image, source);
            return "minimax-task-1";
        };
        GenerationTaskSubmissionCoordinator coordinator = new GenerationTaskSubmissionCoordinator(
                repository,
                new RecordingProjection(),
                Clock.fixed(Instant.parse("2026-07-14T00:00:00Z"), ZoneOffset.UTC)
        );
        SubmitImageToVideoService service = new SubmitImageToVideoService(
                resolver, new ImageToVideoSourceValidator(), submissionPort, coordinator
        );

        var result = service.execute(new SubmitImageToVideoCommand(
                1L, "session-1", "image-1", "让它动起来", "人物自然走动", null, null, null,
                false, false, false
        ));

        GenerationTask task = repository.findById(result.taskId()).orElseThrow();
        assertEquals(GenerationType.IMAGE_TO_VIDEO, task.generationType());
        assertEquals("image-1", ((com.h.backend.generation.domain.model.ImageToVideoSpec) task.spec()).sourceResourceId());
        assertEquals("minimax-task-1", task.providerTaskId());
    }

    private static final class RecordingProjection implements GenerationChatProjectionPort {
        @Override
        public Long createPendingMessage(GenerationTask task) {
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
