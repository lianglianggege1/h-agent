package com.h.capabilities.generation.application.port.in;

import com.h.capabilities.generation.application.command.SubmitImageToVideoCommand;
import com.h.capabilities.generation.application.result.SubmitGenerationResult;

public interface SubmitImageToVideoUseCase {

    SubmitGenerationResult execute(SubmitImageToVideoCommand command);
}
