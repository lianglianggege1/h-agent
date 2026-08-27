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
import com.h.backend.chat.application.ResourceContentPolicy;
import com.h.backend.chat.application.reference.ImageDataUrlEncoder;
import com.h.backend.chat.application.reference.ReferenceImageResolver;
import com.h.backend.chat.application.reference.ResolvedReferenceImage;
import com.h.backend.chat.infrastructure.content.ResourceContentInspector;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceStorageErrorKind;
import com.h.backend.chat.infrastructure.storage.ResourceStorageException;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.common.exception.BusinessException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
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
    private final ResourceContentInspector contentInspector;
    private final ResourceContentPolicy contentPolicy;

    @Autowired
    public ImageGenerationServiceImpl(
            MiniMaxImageClient miniMaxImageClient,
            ResourceWriteCoordinator writeCoordinator,
            ChatSessionService chatSessionService,
            TransactionTemplate transactionTemplate,
            ImageGenerationProperties properties,
            ReferenceImageResolver referenceImageResolver,
            ChatResourceUrls chatResourceUrls,
            ResourceContentInspector contentInspector,
            ResourceContentPolicy contentPolicy
    ) {
        this.miniMaxImageClient = miniMaxImageClient;
        this.writeCoordinator = writeCoordinator;
        this.chatSessionService = chatSessionService;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.referenceImageResolver = referenceImageResolver;
        this.chatResourceUrls = chatResourceUrls;
        this.contentInspector = contentInspector;
        this.contentPolicy = contentPolicy;
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
                // 审查修复第 3 项（计划 §6.2/§6.3）：图片生成调用方在保存前完成
                // 内容校验——provider 声明的 MIME 只是提示，签名冲突即拒绝；
                // 校验失败抛明确业务异常，事务 rollback 补偿已保存图片。
                verifyGeneratedImage(generatedImage);
                // 宽高用 ImageIO 从字节服务端解析（调用方允许，禁止的是存储模块）；
                // 解析失败（如渐进式/特殊编码）回退 provider 声明值，可能为 null（未知）。
                // 注意不得用三元表达式混合 int/Integer 分支（会隐式拆箱，null 时 NPE）。
                BufferedImage decoded = decode(generatedImage.imageBytes());
                Integer width;
                Integer height;
                if (decoded != null) {
                    width = decoded.getWidth();
                    height = decoded.getHeight();
                } else {
                    width = generatedImage.width();
                    height = generatedImage.height();
                }
                resources.add(writeCoordinator.saveAndAttach(
                        new ResourceSaveCommand(
                                "IMAGE",
                                generatedImage.imageBytes(),
                                generatedImage.mimeType(),
                                extensionFor(generatedImage.mimeType()),
                                width,
                                height
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

    /** 服务端自产图片的签名校验（审查修复第 3 项）：声明与签名冲突即拒绝。 */
    private void verifyGeneratedImage(MiniMaxImageGenerationResult.GeneratedImage generatedImage) {
        ResourceContentInspector.Inspection inspection;
        try {
            inspection = contentInspector.inspect(
                    new ByteArrayInputStream(generatedImage.imageBytes()), generatedImage.mimeType());
        } catch (IOException exception) {
            throw new ResourceStorageException(
                    ResourceStorageErrorKind.IO_ERROR, "图片生成结果读取失败", exception);
        }
        ResourceContentPolicy.SaveDecision decision =
                contentPolicy.validateForSave(inspection.result(), generatedImage.mimeType());
        if (!decision.allowed()) {
            throw new BusinessException(40000, "图片生成结果未通过内容校验，已放弃保存");
        }
    }

    /**
     * ImageIO 解析宽高：字节已在内存中（byte[] 形态），解码成本可接受；
     * 无法解码（如 WebP 无内置 reader）返回 null，由调用方回退 provider 声明值。
     */
    private BufferedImage decode(byte[] imageBytes) {
        try {
            return ImageIO.read(new ByteArrayInputStream(imageBytes));
        } catch (IOException | RuntimeException exception) {
            return null;
        }
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
