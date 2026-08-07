package com.h.backend.generation.application.port.out;

import com.h.backend.chat.application.reference.ResolvedReferenceImage;
import com.h.backend.generation.domain.model.ImageToVideoSpec;

public interface ImageToVideoSubmissionPort {

    String submit(ImageToVideoSpec spec, ResolvedReferenceImage image);
}
