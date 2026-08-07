package com.h.backend.generation.application.service;

import com.h.backend.generation.application.port.out.GenerationChatProjectionPort;
import com.h.backend.generation.application.port.out.GenerationTaskRepository;
import com.h.backend.generation.application.result.SubmitGenerationResult;
import com.h.backend.generation.domain.model.GenerationTask;
import com.h.backend.generation.domain.model.VideoGenerationSpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;

@Component
public class GenerationTaskSubmissionCoordinator {
    private static final Duration FIRST_POLL_DELAY = Duration.ofSeconds(5);

    private final GenerationTaskRepository taskRepository;
    private final GenerationChatProjectionPort chatProjectionPort;
    private final Clock clock;

    @Autowired
    public GenerationTaskSubmissionCoordinator(
            GenerationTaskRepository taskRepository,
            GenerationChatProjectionPort chatProjectionPort
    ) {
        this(taskRepository, chatProjectionPort, Clock.systemUTC());
    }

    GenerationTaskSubmissionCoordinator(
            GenerationTaskRepository taskRepository,
            GenerationChatProjectionPort chatProjectionPort,
            Clock clock
    ) {
        this.taskRepository = taskRepository;
        this.chatProjectionPort = chatProjectionPort;
        this.clock = clock;
    }

    public SubmitGenerationResult submit(
            Long userId,
            String sessionId,
            VideoGenerationSpec spec,
            Supplier<String> providerSubmission
    ) {
        Instant now = clock.instant();
        GenerationTask task = GenerationTask.create(UUID.randomUUID().toString(), userId, sessionId, spec, now);
        taskRepository.save(task);
        try {
            String providerTaskId = providerSubmission.get();
            task.markSubmitted(providerTaskId, now.plus(FIRST_POLL_DELAY), clock.instant());
            taskRepository.save(task);

            Long messageId = chatProjectionPort.createPendingMessage(task);
            task.bindChatMessage(messageId, clock.instant());
            taskRepository.save(task);
            return new SubmitGenerationResult(task.id(), providerTaskId, messageId);
        } catch (RuntimeException exception) {
            task.fail(safeMessage(exception), clock.instant());
            taskRepository.save(task);
            throw exception;
        }
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? "视频任务提交失败" : exception.getMessage();
    }
}
