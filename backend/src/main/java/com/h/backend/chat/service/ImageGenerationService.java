package com.h.backend.chat.service;

import com.h.backend.chat.dto.ChatSessionMessageDto;

public interface ImageGenerationService {

    ChatSessionMessageDto generateImage(ImageGenerationCommand command);

    record ImageGenerationCommand(
            Long userId,
            String sessionId,
            Long promptId,
            String prompt,
            String triggerSource
    ) {
    }
}
