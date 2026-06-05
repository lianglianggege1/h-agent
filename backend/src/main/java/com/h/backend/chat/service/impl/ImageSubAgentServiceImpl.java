package com.h.backend.chat.service.impl;

import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.service.ImageGenerationService;
import com.h.backend.chat.service.ImageSubAgentService;
import org.springframework.stereotype.Service;

@Service
public class ImageSubAgentServiceImpl implements ImageSubAgentService {

    private final ImageGenerationService imageGenerationService;

    public ImageSubAgentServiceImpl(ImageGenerationService imageGenerationService) {
        this.imageGenerationService = imageGenerationService;
    }

    @Override
    public ChatSessionMessageDto generateImage(ImageSubAgentCommand command) {
        return imageGenerationService.generateImage(new ImageGenerationService.ImageGenerationCommand(
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
