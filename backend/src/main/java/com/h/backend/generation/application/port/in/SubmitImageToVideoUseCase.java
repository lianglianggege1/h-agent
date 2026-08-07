package com.h.backend.generation.application.port.in;

import com.h.backend.generation.application.command.SubmitImageToVideoCommand;
import com.h.backend.generation.application.result.SubmitGenerationResult;

public interface SubmitImageToVideoUseCase {

    SubmitGenerationResult execute(SubmitImageToVideoCommand command);
}
