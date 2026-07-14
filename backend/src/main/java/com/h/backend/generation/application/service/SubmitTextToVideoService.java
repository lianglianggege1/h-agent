package com.h.backend.generation.application.service;

import com.h.backend.generation.application.command.SubmitTextToVideoCommand;
import com.h.backend.generation.application.port.in.SubmitTextToVideoUseCase;
import com.h.backend.generation.application.port.out.GenerationChatProjectionPort;
import com.h.backend.generation.application.port.out.TextToVideoSubmissionPort;
import com.h.backend.generation.application.result.SubmitGenerationResult;
import com.h.backend.generation.domain.model.GenerationTask;
import com.h.backend.generation.domain.model.TextToVideoSpec;
import com.h.backend.generation.application.port.out.GenerationTaskRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
public class SubmitTextToVideoService implements SubmitTextToVideoUseCase {
    private static final Duration FIRST_POLL_DELAY = Duration.ofSeconds(5);

    private final GenerationTaskRepository taskRepository;
    private final TextToVideoSubmissionPort submissionPort;
    private final GenerationChatProjectionPort chatProjectionPort;
    private final Clock clock;

    @Autowired
    public SubmitTextToVideoService(
            GenerationTaskRepository taskRepository,
            TextToVideoSubmissionPort submissionPort,
            GenerationChatProjectionPort chatProjectionPort
    ) {
        this(taskRepository, submissionPort, chatProjectionPort, Clock.systemUTC());
    }

    SubmitTextToVideoService(
            GenerationTaskRepository taskRepository,
            TextToVideoSubmissionPort submissionPort,
            GenerationChatProjectionPort chatProjectionPort,
            Clock clock
    ) {
        this.taskRepository = taskRepository;
        this.submissionPort = submissionPort;
        this.chatProjectionPort = chatProjectionPort;
        this.clock = clock;
    }

    @Override
    @Transactional
    public SubmitGenerationResult execute(SubmitTextToVideoCommand command) {
        TextToVideoSpec spec = createSpec(command);
        Instant now = clock.instant();
        GenerationTask task = GenerationTask.create(
                UUID.randomUUID().toString(), command.userId(), command.sessionId(), spec, now
        );
        taskRepository.save(task);

        try {
            String providerTaskId = submissionPort.submit(spec);
            task.markSubmitted(providerTaskId, now.plus(FIRST_POLL_DELAY), now);
            taskRepository.save(task);

            Long messageId = chatProjectionPort.createPendingMessage(task);
            task.bindChatMessage(messageId, now);
            taskRepository.save(task);
            return new SubmitGenerationResult(task.id(), providerTaskId, messageId);
        } catch (RuntimeException exception) {
            task.fail(safeMessage(exception), clock.instant());
            taskRepository.save(task);
            throw exception;
        }
    }

    private TextToVideoSpec createSpec(SubmitTextToVideoCommand command) {
        String originalPrompt = requirePrompt(command.originalPrompt(), "originalPrompt");
        String submittedPrompt = command.submittedPrompt() == null || command.submittedPrompt().isBlank()
                ? originalPrompt
                : command.submittedPrompt().trim();
        return TextToVideoSpec.withDefaults(
                originalPrompt, submittedPrompt, command.model(), command.durationSeconds(), command.resolution(),
                command.promptOptimizer(), command.fastPretreatment(), command.aigcWatermark()
        );
    }

    private String requirePrompt(String prompt, String field) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return prompt.trim();
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? "视频任务提交失败" : exception.getMessage();
    }
}
