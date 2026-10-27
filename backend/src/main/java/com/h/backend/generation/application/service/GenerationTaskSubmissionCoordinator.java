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
            GenerationChatProjectionPort chatProjectionPort) {
        this(taskRepository, chatProjectionPort, Clock.systemUTC());
    }

    GenerationTaskSubmissionCoordinator(
            GenerationTaskRepository taskRepository,
            GenerationChatProjectionPort chatProjectionPort,
            Clock clock) {
        this.taskRepository = taskRepository;
        this.chatProjectionPort = chatProjectionPort;
        this.clock = clock;
    }

    public SubmitGenerationResult submit(
            Long userId,
            String sessionId,
            VideoGenerationSpec spec,
            Supplier<String> providerSubmission) {
        return submitWithId(
                UUID.randomUUID().toString(), userId, sessionId, spec, providerSubmission);
    }

    public SubmitGenerationResult submitWithId(
            String id,
            Long userId,
            String sessionId,
            VideoGenerationSpec spec,
            Supplier<String> providerSubmission) {
        var existing = taskRepository.findById(id);
        if (existing.isPresent()) {
            var saved = existing.get();
            if (!saved.userId().equals(userId) || !saved.sessionId().equals(sessionId))
                throw new IllegalArgumentException("任务身份冲突");
            return new SubmitGenerationResult(
                    saved.id(), saved.providerTaskId(), saved.chatMessageId());
        }
        Instant now = clock.instant();
        GenerationTask task = GenerationTask.create(id, userId, sessionId, spec, now);
        taskRepository.save(task);
        try {
            // Validate the owned destination and reserve the message before a paid V2 request.
            if (spec instanceof com.h.backend.generation.domain.model.VideoV2Spec) {
                task.bindChatMessage(
                        chatProjectionPort.createPendingMessage(task), clock.instant());
                taskRepository.save(task);
            }
            String providerTaskId = providerSubmission.get();
            task.markSubmitted(providerTaskId, now.plus(FIRST_POLL_DELAY), clock.instant());
            taskRepository.save(task);

            if (task.chatMessageId() == null) {
                task.bindChatMessage(
                        chatProjectionPort.createPendingMessage(task), clock.instant());
                taskRepository.save(task);
            }
            Long messageId = task.chatMessageId();
            if (spec instanceof com.h.backend.generation.domain.model.VideoV2Spec)
                chatProjectionPort.updateMessage(task);
            return new SubmitGenerationResult(task.id(), providerTaskId, messageId);
        } catch (
                com.h.backend.generation.application.port.out.VideoSubmissionUncertainException
                        exception) {
            task.submissionUnknown(exception.getMessage(), clock.instant());
            taskRepository.save(task);
            if (task.chatMessageId() == null) {
                task.bindChatMessage(
                        chatProjectionPort.createPendingMessage(task), clock.instant());
                taskRepository.save(task);
            }
            Long messageId = task.chatMessageId();
            chatProjectionPort.updateMessage(task);
            return new SubmitGenerationResult(task.id(), null, messageId);
        } catch (RuntimeException exception) {
            if (task.providerTaskId() != null) {
                taskRepository.save(task);
                return new SubmitGenerationResult(
                        task.id(), task.providerTaskId(), task.chatMessageId());
            }
            task.fail(safeMessage(exception), clock.instant());
            taskRepository.save(task);
            if (task.chatMessageId() != null) chatProjectionPort.updateMessage(task);
            throw exception;
        }
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? "视频任务提交失败" : exception.getMessage();
    }
}
