package com.h.backend.generation.application.port.out;

import com.h.backend.generation.domain.model.GenerationTask;

public interface GenerationChatProjectionPort {
    Long createPendingMessage(GenerationTask task);
    void updateMessage(GenerationTask task);
}
