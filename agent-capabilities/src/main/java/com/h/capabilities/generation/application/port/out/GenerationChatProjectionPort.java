package com.h.capabilities.generation.application.port.out;

import com.h.capabilities.generation.domain.model.GenerationTask;

public interface GenerationChatProjectionPort {
    Long createPendingMessage(GenerationTask task);
    void updateMessage(GenerationTask task);
}
