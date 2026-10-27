package com.h.backend.chat.application;

import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;

public interface ImageSubAgentService {

    /** 提交持久化后台任务并返回待完成消息，不等待供应商生成图片。 */
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
