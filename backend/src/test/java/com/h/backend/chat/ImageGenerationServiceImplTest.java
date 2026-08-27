package com.h.backend.chat;

import com.h.backend.chat.infrastructure.config.ImageGenerationProperties;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.infrastructure.image.MiniMaxImageClient;
import com.h.backend.chat.infrastructure.image.MiniMaxImageGenerationResult;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatResourceUrls;
import com.h.backend.chat.application.ImageGenerationService;
import com.h.backend.chat.application.ResourceContentPolicy;
import com.h.backend.chat.infrastructure.content.ResourceContentInspector;
import com.h.backend.chat.application.reference.ReferenceImageResolver;
import com.h.backend.chat.application.reference.ResolvedReferenceImage;
import com.h.backend.chat.application.impl.ImageGenerationServiceImpl;
import com.h.backend.chat.infrastructure.storage.ResourceAttachment;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class ImageGenerationServiceImplTest {

    /** PNG 魔数（非完整图片：签名可验，ImageIO 解析失败回退 provider 声明宽高）。 */
    private static final byte[] PNG_SIGNATURE = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D
    };

    /** JPEG 魔数。 */
    private static final byte[] JPEG_SIGNATURE = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10
    };

    /** 完整有效的 1×1 像素 PNG（ImageIO 可解析出真实宽高）。 */
    private static final byte[] ONE_BY_ONE_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @Test
    void shouldGenerateImageStoreResourceAndAppendImageMessage() {
        MiniMaxImageClient miniMaxImageClient = mock(MiniMaxImageClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ReferenceImageResolver referenceImageResolver = mock(ReferenceImageResolver.class);
        ImageGenerationService service = new ImageGenerationServiceImpl(
                miniMaxImageClient,
                writeCoordinator,
                chatSessionService,
                transactionTemplate,
                new ImageGenerationProperties(null),
                referenceImageResolver,
                new ChatResourceUrls(""),
                new ResourceContentInspector(),
                new ResourceContentPolicy()
        );

        MiniMaxImageGenerationResult generationResult = new MiniMaxImageGenerationResult(
                "provider-123",
                "image/png",
                "image-01",
                PNG_SIGNATURE,
                1024,
                1024,
                "{\"id\":\"provider-123\"}"
        );
        StoredResource storedResource = new StoredResource(
                "resource-1",
                "OBJECT_STORAGE",
                "generated-images/2026/06/03/resource-1.png",
                "image/png",
                "generated-resource-1.png",
                3L,
                1024,
                1024
        );

        ChatSessionMessageDto appended = new ChatSessionMessageDto(
                "501",
                "assistant",
                "IMAGE",
                "一只白猫",
                null,
                List.of(new ChatMessageResourceDto(
                        "resource-1",
                        "IMAGE",
                        "GENERATED",
                        "/api/chat/resources/resource-1/content",
                        "/api/chat/resources/resource-1/download",
                        "generated-resource-1.png",
                        "image/png",
                        3L,
                        1024,
                        1024
                )),
                LocalDateTime.now()
        );

        when(miniMaxImageClient.generate(any())).thenReturn(generationResult);
        // mock 边界（任务 3）：调用方测试 mock Coordinator；
        // attachment 回调同步执行（单图：save 与挂接同事务，rollback 时对象被补偿）。
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<ChatMessageResourceDto> attachment = invocation.getArgument(1);
                    return attachment.attach(storedResource);
                });
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(chatSessionService.appendImageMessage(
                org.mockito.Mockito.eq(1L),
                org.mockito.Mockito.eq("session-1"),
                org.mockito.Mockito.eq("一只白猫"),
                any(),
                any()
        )).thenReturn(appended);

        ChatSessionMessageDto message = service.generateImage(
                new ImageGenerationService.ImageGenerationCommand(
                        1L,
                        "session-1",
                        22L,
                        "一只白猫",
                        "COMMAND"
                )
        );

        assertEquals("IMAGE", message.messageType());
        assertEquals("/api/chat/resources/resource-1/content", message.resources().getFirst().viewUrl());
        verify(miniMaxImageClient).generate(any());
        verify(writeCoordinator).saveAndAttach(any(ResourceSaveCommand.class), any());
        verify(chatSessionService).appendImageMessage(
                eq(1L),
                eq("session-1"),
                eq("一只白猫"),
                any(),
                argThat(resources ->
                        resources.size() == 1
                                && "OBJECT_STORAGE".equals(resources.getFirst().storageType())
                                && "generated-images/2026/06/03/resource-1.png".equals(resources.getFirst().storageKey()))
        );
    }

    @Test
    void shouldPersistImageEditContextInPayload() {
        MiniMaxImageClient miniMaxImageClient = mock(MiniMaxImageClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ReferenceImageResolver referenceImageResolver = mock(ReferenceImageResolver.class);
        ImageGenerationService service = new ImageGenerationServiceImpl(
                miniMaxImageClient,
                writeCoordinator,
                chatSessionService,
                transactionTemplate,
                new ImageGenerationProperties(null),
                referenceImageResolver,
                new ChatResourceUrls(""),
                new ResourceContentInspector(),
                new ResourceContentPolicy()
        );

        when(miniMaxImageClient.generate(any())).thenReturn(new MiniMaxImageGenerationResult(
                "provider-456",
                "image/png",
                "image-01",
                PNG_SIGNATURE,
                null,
                null,
                "{\"id\":\"provider-456\"}"
        ));
        StoredResource storedResource = new StoredResource(
                "resource-2",
                "OBJECT_STORAGE",
                "generated-images/2026/06/05/resource-2.png",
                "image/png",
                "generated-resource-2.png",
                3L,
                null,
                null
        );
        when(referenceImageResolver.resolve(1L, "resource-1")).thenReturn(new ResolvedReferenceImage(
                "resource-1", "image/png", new byte[]{7, 8, 9}, 3L, 512, 512
        ));

        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<ChatMessageResourceDto> attachment = invocation.getArgument(1);
                    return attachment.attach(storedResource);
                });
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(chatSessionService.appendImageMessage(eq(1L), eq("session-1"), eq("把衣服改成黑色"), any(), any()))
                .thenReturn(new ChatSessionMessageDto("502", "assistant", "IMAGE", "把衣服改成黑色", null, List.of(), LocalDateTime.now()));

        service.generateImage(new ImageGenerationService.ImageGenerationCommand(
                1L,
                "session-1",
                22L,
                "把衣服改成黑色",
                "TOOL",
                "resource-1",
                "501",
                "EDIT_PROMPT"
        ));

        ArgumentCaptor<com.h.backend.chat.domain.model.ChatMessagePayload> payloadCaptor =
                ArgumentCaptor.forClass(com.h.backend.chat.domain.model.ChatMessagePayload.class);
        verify(chatSessionService).appendImageMessage(
                eq(1L),
                eq("session-1"),
                eq("把衣服改成黑色"),
                payloadCaptor.capture(),
                any()
        );
        assertEquals("resource-1", payloadCaptor.getValue().getSourceResourceId());
        assertEquals("501", payloadCaptor.getValue().getParentImageMessageId());
        assertEquals("EDIT_PROMPT", payloadCaptor.getValue().getOperationType());
    }

    @Test
    void shouldRejectReferenceResourceOwnedByAnotherUser() {
        MiniMaxImageClient miniMaxImageClient = mock(MiniMaxImageClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ReferenceImageResolver referenceImageResolver = mock(ReferenceImageResolver.class);
        ImageGenerationService service = new ImageGenerationServiceImpl(
                miniMaxImageClient,
                writeCoordinator,
                chatSessionService,
                transactionTemplate,
                new ImageGenerationProperties(null),
                referenceImageResolver,
                new ChatResourceUrls(""),
                new ResourceContentInspector(),
                new ResourceContentPolicy()
        );
        when(referenceImageResolver.resolve(1L, "resource-1"))
                .thenThrow(new IllegalArgumentException("参考图片资源不存在: resource-1"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.generateImage(new ImageGenerationService.ImageGenerationCommand(
                        1L,
                        "session-1",
                        22L,
                        "参考这张图",
                        "COMMAND",
                        "resource-1",
                        null,
                        "GENERATE"
                ))
        );

        assertEquals("参考图片资源不存在: resource-1", error.getMessage());
    }

    @Test
    void shouldRejectNonImageReferenceResource() {
        MiniMaxImageClient miniMaxImageClient = mock(MiniMaxImageClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ReferenceImageResolver referenceImageResolver = mock(ReferenceImageResolver.class);
        ImageGenerationService service = new ImageGenerationServiceImpl(
                miniMaxImageClient,
                writeCoordinator,
                chatSessionService,
                transactionTemplate,
                new ImageGenerationProperties(null),
                referenceImageResolver,
                new ChatResourceUrls(""),
                new ResourceContentInspector(),
                new ResourceContentPolicy()
        );
        when(referenceImageResolver.resolve(1L, "resource-1"))
                .thenThrow(new IllegalArgumentException("参考资源必须是图片: resource-1"));

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () ->
                service.generateImage(new ImageGenerationService.ImageGenerationCommand(
                        1L,
                        "session-1",
                        22L,
                        "参考这张图",
                        "COMMAND",
                        "resource-1",
                        null,
                        "GENERATE"
                ))
        );

        assertEquals("参考资源必须是图片: resource-1", error.getMessage());
    }

    @Test
    void shouldStoreEveryGeneratedImageAndAppendOneImageMessageWithMultipleResources() {
        MiniMaxImageClient miniMaxImageClient = mock(MiniMaxImageClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ReferenceImageResolver referenceImageResolver = mock(ReferenceImageResolver.class);
        ImageGenerationService service = new ImageGenerationServiceImpl(
                miniMaxImageClient,
                writeCoordinator,
                chatSessionService,
                transactionTemplate,
                new ImageGenerationProperties(null),
                referenceImageResolver,
                new ChatResourceUrls(""),
                new ResourceContentInspector(),
                new ResourceContentPolicy()
        );

        when(miniMaxImageClient.generate(any())).thenReturn(new MiniMaxImageGenerationResult(
                "provider-789",
                "image/jpeg",
                "image-01",
                JPEG_SIGNATURE,
                null,
                null,
                "{\"id\":\"provider-789\"}",
                List.of(
                        new MiniMaxImageGenerationResult.GeneratedImage("image/jpeg", JPEG_SIGNATURE, null, null),
                        new MiniMaxImageGenerationResult.GeneratedImage("image/jpeg", JPEG_SIGNATURE, null, null),
                        new MiniMaxImageGenerationResult.GeneratedImage("image/jpeg", JPEG_SIGNATURE, null, null)
                )
        ));
        List<StoredResource> storedResources = List.of(
                new StoredResource("resource-1", "OBJECT_STORAGE", "generated-images/1.jpg", "image/jpeg", "1.jpg", 1L, null, null),
                new StoredResource("resource-2", "OBJECT_STORAGE", "generated-images/2.jpg", "image/jpeg", "2.jpg", 1L, null, null),
                new StoredResource("resource-3", "OBJECT_STORAGE", "generated-images/3.jpg", "image/jpeg", "3.jpg", 1L, null, null)
        );
        AtomicInteger storedIndex = new AtomicInteger();
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<ChatMessageResourceDto> attachment = invocation.getArgument(1);
                    return attachment.attach(storedResources.get(storedIndex.getAndIncrement()));
                });
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(chatSessionService.appendImageMessage(eq(1L), eq("session-1"), eq("三张图"), any(), any()))
                .thenReturn(new ChatSessionMessageDto("503", "assistant", "IMAGE", "三张图", null, List.of(), LocalDateTime.now()));

        service.generateImage(new ImageGenerationService.ImageGenerationCommand(
                1L,
                "session-1",
                22L,
                "三张图",
                "TOOL"
        ));

        verify(writeCoordinator, times(3)).saveAndAttach(any(ResourceSaveCommand.class), any());
        verify(chatSessionService).appendImageMessage(
                eq(1L),
                eq("session-1"),
                eq("三张图"),
                any(),
                argThat(resources ->
                        resources.size() == 3
                                && "resource-1".equals(resources.get(0).id())
                                && "resource-2".equals(resources.get(1).id())
                                && "resource-3".equals(resources.get(2).id()))
        );
    }

    @Test
    void generateImageWritesImageBytesThroughCoordinatorCommand() {
        MiniMaxImageClient miniMaxImageClient = mock(MiniMaxImageClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ReferenceImageResolver referenceImageResolver = mock(ReferenceImageResolver.class);
        ImageGenerationService service = new ImageGenerationServiceImpl(
                miniMaxImageClient,
                writeCoordinator,
                chatSessionService,
                transactionTemplate,
                new ImageGenerationProperties(null),
                referenceImageResolver,
                new ChatResourceUrls(""),
                new ResourceContentInspector(),
                new ResourceContentPolicy()
        );
        byte[] imageBytes = PNG_SIGNATURE;
        when(miniMaxImageClient.generate(any())).thenReturn(new MiniMaxImageGenerationResult(
                "provider-1", "image/png", "image-01", imageBytes, 1024, 1024, "{}"
        ));
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<ChatMessageResourceDto> attachment = invocation.getArgument(1);
                    return attachment.attach(new StoredResource(
                            "resource-1", "OBJECT_STORAGE", "key-1", "image/png", "generated.png", 3L, 1024, 1024));
                });
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(chatSessionService.appendImageMessage(any(), any(), any(), any(), any()))
                .thenReturn(new ChatSessionMessageDto("1", "assistant", "IMAGE", "提示词", null, List.of(), LocalDateTime.now()));

        service.generateImage(new ImageGenerationService.ImageGenerationCommand(
                1L, "session-1", 22L, "提示词", "COMMAND"
        ));

        // byte[] 形态保留：图片生成结果在内存中已成 byte[]，携带 width/height 元数据
        ArgumentCaptor<ResourceSaveCommand> commandCaptor = ArgumentCaptor.forClass(ResourceSaveCommand.class);
        verify(writeCoordinator).saveAndAttach(commandCaptor.capture(), any());
        ResourceSaveCommand command = commandCaptor.getValue();
        assertEquals("IMAGE", command.resourceType());
        assertEquals(imageBytes, command.content());
        assertEquals("image/png", command.mimeType());
        assertEquals("png", command.extension());
        assertEquals(1024, command.width());
        assertEquals(1024, command.height());
    }

    @Test
    void generateImageRunsAllSavesAndAppendInsideSingleAttachmentTransaction() {
        MiniMaxImageClient miniMaxImageClient = mock(MiniMaxImageClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ReferenceImageResolver referenceImageResolver = mock(ReferenceImageResolver.class);
        ImageGenerationService service = new ImageGenerationServiceImpl(
                miniMaxImageClient,
                writeCoordinator,
                chatSessionService,
                transactionTemplate,
                new ImageGenerationProperties(null),
                referenceImageResolver,
                new ChatResourceUrls(""),
                new ResourceContentInspector(),
                new ResourceContentPolicy()
        );
        List<String> order = new ArrayList<>();
        when(miniMaxImageClient.generate(any())).thenReturn(new MiniMaxImageGenerationResult(
                "provider-1", "image/png", "image-01", PNG_SIGNATURE, null, null, "{}"
        ));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            order.add("tx-begin");
            TransactionCallback<?> callback = invocation.getArgument(0);
            Object result = callback.doInTransaction(null);
            order.add("tx-end");
            return result;
        });
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    order.add("save");
                    ResourceAttachment<ChatMessageResourceDto> attachment = invocation.getArgument(1);
                    return attachment.attach(new StoredResource(
                            "resource-1", "OBJECT_STORAGE", "key-1", "image/png", "generated.png", 1L, null, null));
                });
        when(chatSessionService.appendImageMessage(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    order.add("append");
                    return new ChatSessionMessageDto("1", "assistant", "IMAGE", "提示词", null, List.of(), LocalDateTime.now());
                });

        service.generateImage(new ImageGenerationService.ImageGenerationCommand(
                1L, "session-1", 22L, "提示词", "COMMAND"
        ));

        // 所有对象 save 与消息挂接必须在同一事务回调内：
        // 外层 rollback 时 Coordinator 同步器补偿每张已保存图片。
        assertEquals(List.of("tx-begin", "save", "append", "tx-end"), order);
    }

    @Test
    void generateImagePropagatesAppendFailureForTransactionCompensation() {
        MiniMaxImageClient miniMaxImageClient = mock(MiniMaxImageClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ReferenceImageResolver referenceImageResolver = mock(ReferenceImageResolver.class);
        ImageGenerationService service = new ImageGenerationServiceImpl(
                miniMaxImageClient,
                writeCoordinator,
                chatSessionService,
                transactionTemplate,
                new ImageGenerationProperties(null),
                referenceImageResolver,
                new ChatResourceUrls(""),
                new ResourceContentInspector(),
                new ResourceContentPolicy()
        );
        when(miniMaxImageClient.generate(any())).thenReturn(new MiniMaxImageGenerationResult(
                "provider-1", "image/png", "image-01", PNG_SIGNATURE, null, null, "{}"
        ));
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<ChatMessageResourceDto> attachment = invocation.getArgument(1);
                    return attachment.attach(new StoredResource(
                            "resource-1", "OBJECT_STORAGE", "key-1", "image/png", "generated.png", 1L, null, null));
                });
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        IllegalStateException boom = new IllegalStateException("消息挂接失败");
        when(chatSessionService.appendImageMessage(any(), any(), any(), any(), any())).thenThrow(boom);

        // 挂接失败必须原样上抛（由外层事务 rollback 触发对象补偿），不得被吞。
        IllegalStateException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> service.generateImage(new ImageGenerationService.ImageGenerationCommand(
                        1L, "session-1", 22L, "提示词", "COMMAND"))
        );
        org.junit.jupiter.api.Assertions.assertEquals(boom, thrown);
    }

    @Test
    void rejectsGeneratedImageWhenSignatureDoesNotMatchDeclaredMime() {
        // 审查修复第 3 项：provider 声明 image/png 但字节实际是 JPEG ——
        // 模型提供的 MIME 只是提示，签名冲突即拒绝保存，且不进入写入路径。
        MiniMaxImageClient miniMaxImageClient = mock(MiniMaxImageClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ReferenceImageResolver referenceImageResolver = mock(ReferenceImageResolver.class);
        ImageGenerationService service = new ImageGenerationServiceImpl(
                miniMaxImageClient,
                writeCoordinator,
                chatSessionService,
                transactionTemplate,
                new ImageGenerationProperties(null),
                referenceImageResolver,
                new ChatResourceUrls(""),
                new ResourceContentInspector(),
                new ResourceContentPolicy()
        );
        when(miniMaxImageClient.generate(any())).thenReturn(new MiniMaxImageGenerationResult(
                "provider-1", "image/png", "image-01", JPEG_SIGNATURE, 1024, 1024, "{}"
        ));
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.generateImage(new ImageGenerationService.ImageGenerationCommand(
                        1L, "session-1", 22L, "提示词", "COMMAND"))
        );

        assertEquals("图片生成结果未通过内容校验，已放弃保存", error.getMessage());
        verify(writeCoordinator, never()).saveAndAttach(any(ResourceSaveCommand.class), any());
        verify(chatSessionService, never()).appendImageMessage(any(), any(), any(), any(), any());
    }

    @Test
    void parsesImageDimensionsServerSideFromGeneratedBytes() {
        // 计划 §6.2：图片生成调用方用 ImageIO 从字节解析真实宽高传入命令；
        // provider 声明的 1024×1024 只是提示，服务端解析出的 1×1 覆盖声明值。
        MiniMaxImageClient miniMaxImageClient = mock(MiniMaxImageClient.class);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ReferenceImageResolver referenceImageResolver = mock(ReferenceImageResolver.class);
        ImageGenerationService service = new ImageGenerationServiceImpl(
                miniMaxImageClient,
                writeCoordinator,
                chatSessionService,
                transactionTemplate,
                new ImageGenerationProperties(null),
                referenceImageResolver,
                new ChatResourceUrls(""),
                new ResourceContentInspector(),
                new ResourceContentPolicy()
        );
        when(miniMaxImageClient.generate(any())).thenReturn(new MiniMaxImageGenerationResult(
                "provider-1", "image/png", "image-01", ONE_BY_ONE_PNG, 1024, 1024, "{}"
        ));
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<ChatMessageResourceDto> attachment = invocation.getArgument(1);
                    return attachment.attach(new StoredResource(
                            "resource-1", "OBJECT_STORAGE", "key-1", "image/png", "generated.png", 1L, null, null));
                });
        when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        when(chatSessionService.appendImageMessage(any(), any(), any(), any(), any()))
                .thenReturn(new ChatSessionMessageDto("1", "assistant", "IMAGE", "提示词", null, List.of(), LocalDateTime.now()));

        service.generateImage(new ImageGenerationService.ImageGenerationCommand(
                1L, "session-1", 22L, "提示词", "COMMAND"
        ));

        ArgumentCaptor<ResourceSaveCommand> commandCaptor = ArgumentCaptor.forClass(ResourceSaveCommand.class);
        verify(writeCoordinator).saveAndAttach(commandCaptor.capture(), any());
        ResourceSaveCommand command = commandCaptor.getValue();
        assertEquals(1, command.width(), "宽高必须来自 ImageIO 对字节的服务端解析");
        assertEquals(1, command.height());
    }
}
