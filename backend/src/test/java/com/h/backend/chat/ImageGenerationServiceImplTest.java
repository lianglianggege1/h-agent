package com.h.backend.chat;

import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.image.MiniMaxImageClient;
import com.h.backend.chat.image.MiniMaxImageGenerationResult;
import com.h.backend.chat.service.ChatSessionService;
import com.h.backend.chat.service.ImageGenerationService;
import com.h.backend.chat.service.impl.ImageGenerationServiceImpl;
import com.h.backend.chat.storage.ResourceSaveCommand;
import com.h.backend.chat.storage.ResourceStorage;
import com.h.backend.chat.storage.StoredResource;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageGenerationServiceImplTest {

    @Test
    void shouldGenerateImageStoreResourceAndAppendImageMessage() {
        MiniMaxImageClient miniMaxImageClient = mock(MiniMaxImageClient.class);
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ImageGenerationService service = new ImageGenerationServiceImpl(
                miniMaxImageClient,
                resourceStorage,
                chatSessionService
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
}
