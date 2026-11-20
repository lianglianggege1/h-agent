package com.h.capabilities.generation.application.port.in;

import com.h.capabilities.generation.application.command.SubmitTextToVideoCommand;
import com.h.capabilities.generation.application.result.SubmitGenerationResult;

public interface SubmitTextToVideoUseCase {
    SubmitGenerationResult execute(SubmitTextToVideoCommand command);
}
