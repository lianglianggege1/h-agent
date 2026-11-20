package com.h.capabilities.generation.application.port.out;

import com.h.capabilities.chat.application.reference.ResolvedReferenceImage;
import com.h.capabilities.generation.domain.model.ImageToVideoSpec;

public interface ImageToVideoSubmissionPort {

    String submit(ImageToVideoSpec spec, ResolvedReferenceImage image);
}
