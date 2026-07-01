package com.h.backend.chat.application;

import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;

public interface ImageGenerationService {

    ChatSessionMessageDto generateImage(ImageGenerationCommand command);

    record ImageGenerationCommand(
            Long userId,
            String sessionId,
            Long promptId,
            String prompt,
            String triggerSource,
            String sourceResourceId,
            String parentImageMessageId,
            String operationType
    ) {
        public ImageGenerationCommand(
                Long userId,
                String sessionId,
                Long promptId,
                String prompt,
                String triggerSource
        ) {
            this(userId, sessionId, promptId, prompt, triggerSource, null, null, "GENERATE");
        }
    }
}
