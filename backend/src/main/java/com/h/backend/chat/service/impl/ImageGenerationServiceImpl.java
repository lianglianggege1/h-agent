package com.h.backend.chat.service.impl;

import com.h.backend.chat.config.ImageGenerationProperties;
import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.image.MiniMaxImageClient;
import com.h.backend.chat.image.MiniMaxImageGenerationRequest;
import com.h.backend.chat.image.MiniMaxImageGenerationResult;
import com.h.backend.chat.model.ChatMessagePayload;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.service.ImageGenerationService;
import com.h.backend.chat.storage.ResourceSaveCommand;
import com.h.backend.chat.storage.ResourceStorage;
import com.h.backend.chat.storage.StoredResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ImageGenerationServiceImpl implements ImageGenerationService {

    private static final String PROVIDER = "MINIMAX";
    private static final String READY_STATUS = "READY";

    private final MiniMaxImageClient miniMaxImageClient;
    private final ResourceStorage resourceStorage;
    private final ChatSessionService chatSessionService;
    private final ImageGenerationProperties properties;

    @Autowired
    public ImageGenerationServiceImpl(
            MiniMaxImageClient miniMaxImageClient,
            ResourceStorage resourceStorage,
            ChatSessionService chatSessionService,
            ImageGenerationProperties properties
    ) {
        this.miniMaxImageClient = miniMaxImageClient;
        this.resourceStorage = resourceStorage;
        this.chatSessionService = chatSessionService;
        this.properties = properties;
    }

    public ImageGenerationServiceImpl(
            MiniMaxImageClient miniMaxImageClient,
            ResourceStorage resourceStorage,
            ChatSessionService chatSessionService
    ) {
        this(
                miniMaxImageClient,
                resourceStorage,
                chatSessionService,
                new ImageGenerationProperties(null)
        );
    }

    @Override
    public ChatSessionMessageDto generateImage(ImageGenerationCommand command) {
        String prompt = command.prompt() == null ? "" : command.prompt().trim();
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("图片提示词不能为空");
        }
        ImageGenerationProperties.MiniMax minimax = properties.minimaxOrDefault();
        MiniMaxImageGenerationResult generationResult = miniMaxImageClient.generate(
                new MiniMaxImageGenerationRequest(
                        minimax.model(),
                        prompt,
                        minimax.aspectRatio(),
                        "url",
                        1,
                        minimax.promptOptimizer()
                )
        );
        StoredResource storedResource = resourceStorage.save(new ResourceSaveCommand(
                "IMAGE",
                command.sessionId(),
                prompt,
                generationResult.imageBytes(),
                generationResult.mimeType(),
                extensionFor(generationResult.mimeType()),
                generationResult.width(),
                generationResult.height()
        ));

        ChatMessagePayload payload = new ChatMessagePayload();
        payload.setPrompt(prompt);
        payload.setProvider(PROVIDER);
        payload.setProviderRequestId(generationResult.providerRequestId());
        payload.setModel(generationResult.model());
        payload.setAspectRatio(minimax.aspectRatio());
        payload.setStatus(READY_STATUS);
        payload.setTriggerSource(command.triggerSource());
        payload.setSourceResourceId(command.sourceResourceId());
        payload.setParentImageMessageId(command.parentImageMessageId());
        payload.setOperationType(command.operationType());

        ChatMessageResourceDto resource = new ChatMessageResourceDto(
                storedResource.id(),
                "IMAGE",
                resourceStorage.buildViewUrl(storedResource.id()),
                resourceStorage.buildDownloadUrl(storedResource.id()),
                storedResource.fileName(),
                storedResource.mimeType(),
                storedResource.fileSize(),
                storedResource.width(),
                storedResource.height(),
                storedResource.storageType(),
                storedResource.storageKey(),
                storedResource.sha256()
        );
        return chatSessionService.appendImageMessage(
                command.userId(),
                command.sessionId(),
                prompt,
                payload,
                List.of(resource)
        );
    }

    private String extensionFor(String mimeType) {
        if ("image/jpeg".equalsIgnoreCase(mimeType)) {
            return "jpg";
        }
        if ("image/webp".equalsIgnoreCase(mimeType)) {
            return "webp";
        }
        return "png";
    }
}
