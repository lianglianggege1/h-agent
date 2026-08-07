package com.h.backend.generation.application.service;

import com.h.backend.chat.application.reference.ReferenceImageResolver;
import com.h.backend.chat.application.reference.ResolvedReferenceImage;
import com.h.backend.generation.application.command.SubmitImageToVideoCommand;
import com.h.backend.generation.application.port.in.SubmitImageToVideoUseCase;
import com.h.backend.generation.application.port.out.ImageToVideoSubmissionPort;
import com.h.backend.generation.application.result.SubmitGenerationResult;
import com.h.backend.generation.domain.model.ImageToVideoSpec;
import com.h.backend.generation.domain.service.ImageToVideoSourceValidator;
import org.springframework.stereotype.Service;

@Service
public class SubmitImageToVideoService implements SubmitImageToVideoUseCase {
    private final ReferenceImageResolver referenceImageResolver;
    private final ImageToVideoSourceValidator sourceValidator;
    private final ImageToVideoSubmissionPort submissionPort;
    private final GenerationTaskSubmissionCoordinator submissionCoordinator;

    public SubmitImageToVideoService(
            ReferenceImageResolver referenceImageResolver,
            ImageToVideoSourceValidator sourceValidator,
            ImageToVideoSubmissionPort submissionPort,
            GenerationTaskSubmissionCoordinator submissionCoordinator
    ) {
        this.referenceImageResolver = referenceImageResolver;
        this.sourceValidator = sourceValidator;
        this.submissionPort = submissionPort;
        this.submissionCoordinator = submissionCoordinator;
    }

    @Override
    public SubmitGenerationResult execute(SubmitImageToVideoCommand command) {
        String originalPrompt = requirePrompt(command.originalPrompt(), "originalPrompt");
        String submittedPrompt = command.submittedPrompt() == null || command.submittedPrompt().isBlank()
                ? originalPrompt
                : command.submittedPrompt().trim();
        ResolvedReferenceImage image = referenceImageResolver.resolve(command.userId(), command.referenceResourceId());
        sourceValidator.validate(image);
        ImageToVideoSpec spec = ImageToVideoSpec.withDefaults(
                command.referenceResourceId(), originalPrompt, submittedPrompt, command.model(), command.durationSeconds(),
                command.resolution(), command.promptOptimizer(), command.fastPretreatment(), command.aigcWatermark()
        );
        return submissionCoordinator.submit(
                command.userId(), command.sessionId(), spec, () -> submissionPort.submit(spec, image)
        );
    }

    private String requirePrompt(String prompt, String field) {
        if (prompt == null || prompt.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return prompt.trim();
    }
}
