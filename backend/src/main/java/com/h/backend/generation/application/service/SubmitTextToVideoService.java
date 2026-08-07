package com.h.backend.generation.application.service;

import com.h.backend.generation.application.command.SubmitTextToVideoCommand;
import com.h.backend.generation.application.port.in.SubmitTextToVideoUseCase;
import com.h.backend.generation.application.port.out.TextToVideoSubmissionPort;
import com.h.backend.generation.application.result.SubmitGenerationResult;
import com.h.backend.generation.domain.model.TextToVideoSpec;
import org.springframework.stereotype.Service;

@Service
public class SubmitTextToVideoService implements SubmitTextToVideoUseCase {
    private final TextToVideoSubmissionPort submissionPort;
    private final GenerationTaskSubmissionCoordinator submissionCoordinator;

    public SubmitTextToVideoService(
            TextToVideoSubmissionPort submissionPort,
            GenerationTaskSubmissionCoordinator submissionCoordinator
    ) {
        this.submissionPort = submissionPort;
        this.submissionCoordinator = submissionCoordinator;
    }

    @Override
    public SubmitGenerationResult execute(SubmitTextToVideoCommand command) {
        TextToVideoSpec spec = createSpec(command);
        return submissionCoordinator.submit(
                command.userId(), command.sessionId(), spec, () -> submissionPort.submit(spec)
        );
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

}
