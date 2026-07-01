package com.h.backend.chat.application.impl;

import com.h.backend.chat.infrastructure.config.ImageGenerationProperties;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.image.MiniMaxImageClient;
import com.h.backend.chat.infrastructure.image.MiniMaxImageGenerationRequest;
import com.h.backend.chat.infrastructure.image.MiniMaxImageGenerationResult;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.domain.model.ChatMessagePayload;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ImageGenerationService;
import com.h.backend.chat.infrastructure.storage.ResourceContent;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceStorage;
import com.h.backend.chat.infrastructure.storage.StoredResource;
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
        MiniMaxImageGenerationRequest.SubjectReference subjectReference = buildSubjectReference(
                command.userId(),
                command.sourceResourceId()
        );
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
            String viewUrl = resourceStorage.buildViewUrl(storedResource.id());
            String downloadUrl = resourceStorage.buildDownloadUrl(storedResource.id());
            resources.add(new ChatMessageResourceDto(
                    storedResource.id(),
                    "IMAGE",
                    "GENERATED",
                    viewUrl,
                    downloadUrl,
                    storedResource.fileName(),
                    storedResource.mimeType(),
                    storedResource.fileSize(),
                    storedResource.width(),
                    storedResource.height(),
                    storedResource.storageType(),
                    storedResource.storageKey()
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

    private MiniMaxImageGenerationRequest.SubjectReference buildSubjectReference(Long userId, String sourceResourceId) {
        if (sourceResourceId == null || sourceResourceId.isBlank()) {
            return null;
        }
        ChatMessageResourceEntity resource = chatMessageResourceMapper.selectByResourceId(sourceResourceId);
        if (resource == null || !userId.equals(resource.getUserId())) {
            throw new IllegalArgumentException("参考图片资源不存在: " + sourceResourceId);
        }
        if (!"IMAGE".equalsIgnoreCase(resource.getResourceType())) {
            throw new IllegalArgumentException("参考资源必须是图片: " + sourceResourceId);
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
