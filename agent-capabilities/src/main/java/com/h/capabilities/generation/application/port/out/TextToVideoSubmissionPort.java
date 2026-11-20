package com.h.capabilities.generation.application.port.out;

import com.h.capabilities.generation.domain.model.TextToVideoSpec;

public interface TextToVideoSubmissionPort {
    String submit(TextToVideoSpec spec);
}
