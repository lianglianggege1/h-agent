package com.h.backend.chat;

import com.h.backend.chat.infrastructure.config.ImageGenerationProperties;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.infrastructure.image.MiniMaxImageClient;
import com.h.backend.chat.infrastructure.image.MiniMaxImageGenerationResult;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatResourceUrls;
import com.h.backend.chat.application.ImageGenerationService;
import com.h.backend.chat.application.reference.ReferenceImageResolver;
import com.h.backend.chat.application.reference.ResolvedReferenceImage;
import com.h.backend.chat.application.impl.ImageGenerationServiceImpl;
import com.h.backend.chat.infrastructure.storage.ResourceAttachment;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class ImageGenerationServiceImplTest {

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
                new ImageGenerationProperties(null, null),
                referenceImageResolver,
                new ChatResourceUrls("")
        );

        MiniMaxImageGenerationResult generationResult = new MiniMaxImageGenerationResult(
                "provider-123",
                "image/png",
                "image-01",
                new byte[]{1, 2, 3},
                1024,
                1024,
                "{\"id\":\"provider-123\"}"
        );
        StoredResource storedResource = new StoredResource(
                "resource-1",
                "LOCAL_FILE",
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
                                && "LOCAL_FILE".equals(resources.getFirst().storageType())
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
                new ImageGenerationProperties(null, null),
                referenceImageResolver,
                new ChatResourceUrls("")
        );

        when(miniMaxImageClient.generate(any())).thenReturn(new MiniMaxImageGenerationResult(
                "provider-456",
                "image/png",
                "image-01",
                new byte[]{4, 5, 6},
                null,
                null,
                "{\"id\":\"provider-456\"}"
        ));
        StoredResource storedResource = new StoredResource(
                "resource-2",
                "LOCAL_FILE",
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
                new ImageGenerationProperties(null, null),
                referenceImageResolver,
                new ChatResourceUrls("")
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
                new ImageGenerationProperties(null, null),
                referenceImageResolver,
                new ChatResourceUrls("")
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
                new ImageGenerationProperties(null, null),
                referenceImageResolver,
                new ChatResourceUrls("")
        );

        when(miniMaxImageClient.generate(any())).thenReturn(new MiniMaxImageGenerationResult(
                "provider-789",
                "image/jpeg",
                "image-01",
                new byte[]{1},
                null,
                null,
                "{\"id\":\"provider-789\"}",
                List.of(
                        new MiniMaxImageGenerationResult.GeneratedImage("image/jpeg", new byte[]{1}, null, null),
                        new MiniMaxImageGenerationResult.GeneratedImage("image/jpeg", new byte[]{2}, null, null),
                        new MiniMaxImageGenerationResult.GeneratedImage("image/jpeg", new byte[]{3}, null, null)
                )
        ));
        List<StoredResource> storedResources = List.of(
                new StoredResource("resource-1", "LOCAL_FILE", "generated-images/1.jpg", "image/jpeg", "1.jpg", 1L, null, null),
                new StoredResource("resource-2", "LOCAL_FILE", "generated-images/2.jpg", "image/jpeg", "2.jpg", 1L, null, null),
                new StoredResource("resource-3", "LOCAL_FILE", "generated-images/3.jpg", "image/jpeg", "3.jpg", 1L, null, null)
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
                new ImageGenerationProperties(null, null),
                referenceImageResolver,
                new ChatResourceUrls("")
        );
        byte[] imageBytes = new byte[]{1, 2, 3};
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
                new ImageGenerationProperties(null, null),
                referenceImageResolver,
                new ChatResourceUrls("")
        );
        List<String> order = new ArrayList<>();
        when(miniMaxImageClient.generate(any())).thenReturn(new MiniMaxImageGenerationResult(
                "provider-1", "image/png", "image-01", new byte[]{1}, null, null, "{}"
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
                new ImageGenerationProperties(null, null),
                referenceImageResolver,
                new ChatResourceUrls("")
        );
        when(miniMaxImageClient.generate(any())).thenReturn(new MiniMaxImageGenerationResult(
                "provider-1", "image/png", "image-01", new byte[]{1}, null, null, "{}"
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
}
