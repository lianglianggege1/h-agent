package com.h.backend.generation.application.port.in;

import com.h.backend.generation.application.command.SubmitTextToVideoCommand;
import com.h.backend.generation.application.result.SubmitGenerationResult;

public interface SubmitTextToVideoUseCase {
    SubmitGenerationResult execute(SubmitTextToVideoCommand command);
}
