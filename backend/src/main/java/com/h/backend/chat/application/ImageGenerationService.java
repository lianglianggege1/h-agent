package com.h.backend.chat.application;

import com.h.capabilities.chat.interfaces.dto.ChatSessionMessageDto;

public interface ImageGenerationService {

    ChatSessionMessageDto generateImage(ImageGenerationCommand command);

    /** 后台任务将结果写入预先保留的消息，资源保存与挂接沿用同一事务。 */
    ChatSessionMessageDto generateImage(ImageGenerationCommand command, Long pendingMessageId);

    ChatSessionMessageDto attachRemoteIllustration(ImageGenerationCommand command, byte[] image, String mimeType);

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
