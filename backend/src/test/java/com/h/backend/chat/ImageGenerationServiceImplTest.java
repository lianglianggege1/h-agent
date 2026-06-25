package com.h.backend.chat;

import com.h.backend.chat.config.ImageGenerationProperties;
import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.image.MiniMaxImageClient;
import com.h.backend.chat.image.MiniMaxImageGenerationResult;
import com.h.backend.chat.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.service.ImageGenerationService;
import com.h.backend.chat.service.impl.ImageGenerationServiceImpl;
import com.h.backend.chat.entity.ChatMessageResourceEntity;
import com.h.backend.chat.storage.ResourceContent;
import com.h.backend.chat.storage.ResourceSaveCommand;
import com.h.backend.chat.storage.ResourceStorage;
import com.h.backend.chat.storage.StoredResource;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatMessageResourceMapper chatMessageResourceMapper = mock(ChatMessageResourceMapper.class);
        ImageGenerationService service = new ImageGenerationServiceImpl(
                miniMaxImageClient,
                resourceStorage,
                chatSessionService,
                new ImageGenerationProperties(null, null),
                chatMessageResourceMapper
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
                1024,
                "sha"
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
        when(resourceStorage.save(any(ResourceSaveCommand.class))).thenReturn(storedResource);
        when(resourceStorage.buildViewUrl("resource-1")).thenReturn("/api/chat/resources/resource-1/content");
        when(resourceStorage.buildDownloadUrl("resource-1")).thenReturn("/api/chat/resources/resource-1/download");
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
        verify(resourceStorage).save(any(ResourceSaveCommand.class));
        verify(chatSessionService).appendImageMessage(
                eq(1L),
                eq("session-1"),
                eq("一只白猫"),
                any(),
                argThat(resources ->
                        resources.size() == 1
                                && "LOCAL_FILE".equals(resources.getFirst().storageType())
                                && "generated-images/2026/06/03/resource-1.png".equals(resources.getFirst().storageKey())
                                && "sha".equals(resources.getFirst().sha256()))
        );
    }

    @Test
    void shouldPersistImageEditContextInPayload() {
        MiniMaxImageClient miniMaxImageClient = mock(MiniMaxImageClient.class);
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatMessageResourceMapper chatMessageResourceMapper = mock(ChatMessageResourceMapper.class);
        ImageGenerationService service = new ImageGenerationServiceImpl(
                miniMaxImageClient,
                resourceStorage,
                chatSessionService,
                new ImageGenerationProperties(null, null),
                chatMessageResourceMapper
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
                null,
                "sha2"
        );
        ChatMessageResourceEntity referenceResource = new ChatMessageResourceEntity();
        referenceResource.setId("resource-1");
        referenceResource.setStorageKey("reference-images/resource-1.png");
        referenceResource.setMimeType("image/png");
        when(chatMessageResourceMapper.selectByResourceId("resource-1")).thenReturn(referenceResource);
        when(resourceStorage.open("reference-images/resource-1.png"))
                .thenReturn(new ResourceContent(new ByteArrayInputStream(new byte[]{7, 8, 9}), "image/png", 3L));

        when(resourceStorage.save(any(ResourceSaveCommand.class))).thenReturn(storedResource);
        when(resourceStorage.buildViewUrl("resource-2")).thenReturn("/api/chat/resources/resource-2/content");
        when(resourceStorage.buildDownloadUrl("resource-2")).thenReturn("/api/chat/resources/resource-2/download");
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

        ArgumentCaptor<com.h.backend.chat.model.ChatMessagePayload> payloadCaptor =
                ArgumentCaptor.forClass(com.h.backend.chat.model.ChatMessagePayload.class);
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
    void shouldStoreEveryGeneratedImageAndAppendOneImageMessageWithMultipleResources() {
        MiniMaxImageClient miniMaxImageClient = mock(MiniMaxImageClient.class);
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatMessageResourceMapper chatMessageResourceMapper = mock(ChatMessageResourceMapper.class);
        ImageGenerationService service = new ImageGenerationServiceImpl(
                miniMaxImageClient,
                resourceStorage,
                chatSessionService,
                new ImageGenerationProperties(null, null),
                chatMessageResourceMapper
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
        when(resourceStorage.save(any(ResourceSaveCommand.class)))
                .thenReturn(
                        new StoredResource("resource-1", "LOCAL_FILE", "generated-images/1.jpg", "image/jpeg", "1.jpg", 1L, null, null, "sha1"),
                        new StoredResource("resource-2", "LOCAL_FILE", "generated-images/2.jpg", "image/jpeg", "2.jpg", 1L, null, null, "sha2"),
                        new StoredResource("resource-3", "LOCAL_FILE", "generated-images/3.jpg", "image/jpeg", "3.jpg", 1L, null, null, "sha3")
                );
        when(resourceStorage.buildViewUrl("resource-1")).thenReturn("/api/chat/resources/resource-1/content");
        when(resourceStorage.buildViewUrl("resource-2")).thenReturn("/api/chat/resources/resource-2/content");
        when(resourceStorage.buildViewUrl("resource-3")).thenReturn("/api/chat/resources/resource-3/content");
        when(resourceStorage.buildDownloadUrl("resource-1")).thenReturn("/api/chat/resources/resource-1/download");
        when(resourceStorage.buildDownloadUrl("resource-2")).thenReturn("/api/chat/resources/resource-2/download");
        when(resourceStorage.buildDownloadUrl("resource-3")).thenReturn("/api/chat/resources/resource-3/download");
        when(chatSessionService.appendImageMessage(eq(1L), eq("session-1"), eq("三张图"), any(), any()))
                .thenReturn(new ChatSessionMessageDto("503", "assistant", "IMAGE", "三张图", null, List.of(), LocalDateTime.now()));

        service.generateImage(new ImageGenerationService.ImageGenerationCommand(
                1L,
                "session-1",
                22L,
                "三张图",
                "TOOL"
        ));

        verify(resourceStorage, times(3)).save(any(ResourceSaveCommand.class));
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
}
