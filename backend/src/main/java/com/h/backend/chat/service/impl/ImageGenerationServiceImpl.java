package com.h.backend.chat.service.impl;

import com.h.backend.chat.config.ImageGenerationProperties;
import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.entity.ChatMessageResourceEntity;
import com.h.backend.chat.image.MiniMaxImageClient;
import com.h.backend.chat.image.MiniMaxImageGenerationRequest;
import com.h.backend.chat.image.MiniMaxImageGenerationResult;
import com.h.backend.chat.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.model.ChatMessagePayload;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.service.ImageGenerationService;
import com.h.backend.chat.storage.ResourceContent;
import com.h.backend.chat.storage.ResourceSaveCommand;
import com.h.backend.chat.storage.ResourceStorage;
import com.h.backend.chat.storage.StoredResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

@Service
public class ImageGenerationServiceImpl implements ImageGenerationService {

    private static final String PROVIDER = "MINIMAX";
    private static final String READY_STATUS = "READY";

    private final MiniMaxImageClient miniMaxImageClient;
    private final ResourceStorage resourceStorage;
    private final ChatSessionService chatSessionService;
    private final ImageGenerationProperties properties;
    private final ChatMessageResourceMapper chatMessageResourceMapper;

    @Autowired
    public ImageGenerationServiceImpl(
            MiniMaxImageClient miniMaxImageClient,
            ResourceStorage resourceStorage,
            ChatSessionService chatSessionService,
            ImageGenerationProperties properties,
            ChatMessageResourceMapper chatMessageResourceMapper
    ) {
        this.miniMaxImageClient = miniMaxImageClient;
        this.resourceStorage = resourceStorage;
        this.chatSessionService = chatSessionService;
        this.properties = properties;
        this.chatMessageResourceMapper = chatMessageResourceMapper;
    }

    @Override
    public ChatSessionMessageDto generateImage(ImageGenerationCommand command) {
        String prompt = command.prompt() == null ? "" : command.prompt().trim();
        if (prompt.isBlank()) {
            throw new IllegalArgumentException("图片提示词不能为空");
        }
        ImageGenerationProperties.MiniMax minimax = properties.minimaxOrDefault();
        MiniMaxImageGenerationRequest.SubjectReference subjectReference = buildSubjectReference(command.sourceResourceId());
        MiniMaxImageGenerationResult generationResult = miniMaxImageClient.generate(
                new MiniMaxImageGenerationRequest(
                        minimax.model(),
                        prompt,
                        minimax.aspectRatio(),
                        "url",
                        minimax.n(),
                        minimax.promptOptimizer(),
                        subjectReference
                )
        );
        List<ChatMessageResourceDto> resources = new ArrayList<>();
        for (MiniMaxImageGenerationResult.GeneratedImage generatedImage : generationResult.images()) {
            StoredResource storedResource = resourceStorage.save(new ResourceSaveCommand(
                    "IMAGE",
                    command.sessionId(),
                    prompt,
                    generatedImage.imageBytes(),
                    generatedImage.mimeType(),
                    extensionFor(generatedImage.mimeType()),
                    generatedImage.width(),
                    generatedImage.height()
            ));
            resources.add(new ChatMessageResourceDto(
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
            ));
        }

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

        return chatSessionService.appendImageMessage(
                command.userId(),
                command.sessionId(),
                prompt,
                payload,
                resources
        );
    }

    private MiniMaxImageGenerationRequest.SubjectReference buildSubjectReference(String sourceResourceId) {
        if (sourceResourceId == null || sourceResourceId.isBlank()) {
            return null;
        }
        ChatMessageResourceEntity resource = chatMessageResourceMapper.selectByResourceId(sourceResourceId);
        if (resource == null) {
            throw new IllegalArgumentException("参考图片资源不存在: " + sourceResourceId);
        }
        ResourceContent content = resourceStorage.open(resource.getStorageKey());
        try (InputStream inputStream = content.inputStream()) {
            byte[] bytes = inputStream.readAllBytes();
            String base64 = Base64.getEncoder().encodeToString(bytes);
            String mimeType = content.mimeType() != null ? content.mimeType() : resource.getMimeType();
            return new MiniMaxImageGenerationRequest.SubjectReference(
                    "character",
                    "data:" + mimeType + ";base64," + base64
            );
        } catch (IOException ex) {
            throw new IllegalStateException("读取参考图片失败: " + sourceResourceId, ex);
        }
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
