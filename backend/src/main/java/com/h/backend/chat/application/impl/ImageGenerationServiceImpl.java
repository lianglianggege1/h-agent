package com.h.backend.chat.application.impl;

import com.h.backend.chat.infrastructure.config.ImageGenerationProperties;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.infrastructure.image.MiniMaxImageClient;
import com.h.backend.chat.infrastructure.image.MiniMaxImageGenerationRequest;
import com.h.backend.chat.infrastructure.image.MiniMaxImageGenerationResult;
import com.h.backend.chat.domain.model.ChatMessagePayload;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatResourceUrls;
import com.h.backend.chat.application.ImageGenerationService;
import com.h.backend.chat.application.reference.ImageDataUrlEncoder;
import com.h.backend.chat.application.reference.ReferenceImageResolver;
import com.h.backend.chat.application.reference.ResolvedReferenceImage;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

@Service
public class ImageGenerationServiceImpl implements ImageGenerationService {

    private static final String PROVIDER = "MINIMAX";
    private static final String READY_STATUS = "READY";

    private final MiniMaxImageClient miniMaxImageClient;
    private final ResourceWriteCoordinator writeCoordinator;
    private final ChatSessionService chatSessionService;
    private final TransactionTemplate transactionTemplate;
    private final ImageGenerationProperties properties;
    private final ReferenceImageResolver referenceImageResolver;
    private final ChatResourceUrls chatResourceUrls;

    @Autowired
    public ImageGenerationServiceImpl(
            MiniMaxImageClient miniMaxImageClient,
            ResourceWriteCoordinator writeCoordinator,
            ChatSessionService chatSessionService,
            TransactionTemplate transactionTemplate,
            ImageGenerationProperties properties,
            ReferenceImageResolver referenceImageResolver,
            ChatResourceUrls chatResourceUrls
    ) {
        this.miniMaxImageClient = miniMaxImageClient;
        this.writeCoordinator = writeCoordinator;
        this.chatSessionService = chatSessionService;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.referenceImageResolver = referenceImageResolver;
        this.chatResourceUrls = chatResourceUrls;
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

        // 新计划任务 3：provider HTTP 调用留在事务外；所有对象写入与消息挂接
        // 在同一事务内（Coordinator 加入本事务，rollback 时每张图被补偿删除）。
        return transactionTemplate.execute(status -> {
            List<ChatMessageResourceDto> resources = new ArrayList<>();
            for (MiniMaxImageGenerationResult.GeneratedImage generatedImage : generationResult.images()) {
                resources.add(writeCoordinator.saveAndAttach(
                        new ResourceSaveCommand(
                                "IMAGE",
                                generatedImage.imageBytes(),
                                generatedImage.mimeType(),
                                extensionFor(generatedImage.mimeType()),
                                generatedImage.width(),
                                generatedImage.height()
                        ),
                        this::toResourceDto
                ));
            }
            return chatSessionService.appendImageMessage(
                    command.userId(),
                    command.sessionId(),
                    prompt,
                    payload,
                    resources
            );
        });
    }

    private ChatMessageResourceDto toResourceDto(StoredResource storedResource) {
        return new ChatMessageResourceDto(
                storedResource.id(),
                "IMAGE",
                "GENERATED",
                chatResourceUrls.view(storedResource.id()),
                chatResourceUrls.download(storedResource.id()),
                storedResource.fileName(),
                storedResource.mimeType(),
                storedResource.fileSize(),
                storedResource.width(),
                storedResource.height(),
                storedResource.storageType(),
                storedResource.storageKey()
        );
    }

    private MiniMaxImageGenerationRequest.SubjectReference buildSubjectReference(Long userId, String sourceResourceId) {
        if (sourceResourceId == null || sourceResourceId.isBlank()) {
            return null;
        }
        ResolvedReferenceImage image = referenceImageResolver.resolve(userId, sourceResourceId);
        return new MiniMaxImageGenerationRequest.SubjectReference("character", ImageDataUrlEncoder.encode(image));
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
