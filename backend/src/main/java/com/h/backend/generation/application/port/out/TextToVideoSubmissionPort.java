package com.h.backend.generation.application.port.out;

import com.h.backend.generation.domain.model.TextToVideoSpec;

public interface TextToVideoSubmissionPort {
    String submit(TextToVideoSpec spec);
}
