package com.h.backend.chat.application.impl;

import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.application.ImageGenerationService;
import com.h.backend.chat.application.ImageSubAgentService;
import org.springframework.stereotype.Service;

@Service
public class ImageSubAgentServiceImpl implements ImageSubAgentService {

    private final ImageGenerationTaskService imageGenerationTaskService;

    public ImageSubAgentServiceImpl(ImageGenerationTaskService imageGenerationTaskService) {
        this.imageGenerationTaskService = imageGenerationTaskService;
    }

    @Override
    public ChatSessionMessageDto generateImage(ImageSubAgentCommand command) {
        return imageGenerationTaskService.submit(new ImageGenerationService.ImageGenerationCommand(
                command.userId(),
                command.sessionId(),
                command.promptId(),
                command.instruction(),
                command.triggerSource(),
                command.sourceResourceId(),
                command.parentImageMessageId(),
                command.operationType()
        ));
    }
}
