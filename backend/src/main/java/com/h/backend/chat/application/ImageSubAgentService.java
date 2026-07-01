package com.h.backend.chat.application;

import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;

public interface ImageSubAgentService {

    ChatSessionMessageDto generateImage(ImageSubAgentCommand command);

    record ImageSubAgentCommand(
            Long userId,
            String sessionId,
            Long promptId,
            String instruction,
            String triggerSource,
            String sourceResourceId,
            String parentImageMessageId,
            String operationType
    ) {
        public ImageSubAgentCommand(
                Long userId,
                String sessionId,
                Long promptId,
                String instruction,
                String triggerSource
        ) {
            this(userId, sessionId, promptId, instruction, triggerSource, null, null, "GENERATE");
        }
    }
}
