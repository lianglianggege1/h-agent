package com.h.backend.chat;

import com.h.backend.chat.infrastructure.config.ResourceUploadProperties;
import com.h.backend.chat.interfaces.web.ChatResourceController;
import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.application.ChatResourceService;
import com.h.backend.chat.application.impl.ChatResourceServiceImpl;
import com.h.backend.chat.infrastructure.storage.ResourceContent;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceStorage;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.NoSuchFileException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class ChatResourceControllerTest {

    private final ChatResourceService chatResourceService = mock(ChatResourceService.class);
    private final ResourceStorage resourceStorage = mock(ResourceStorage.class);
    private final ChatMessageResourceMapper chatMessageResourceMapper = mock(ChatMessageResourceMapper.class);
    private final ResourceUploadProperties uploadProperties = new ResourceUploadProperties();
    private final ChatResourceController controller = new ChatResourceController(
            chatResourceService, resourceStorage, chatMessageResourceMapper, uploadProperties
    );

    @Test
    void shouldReturnPreviewResourceWithImageContentType() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(chatResourceService.openPreview(1L, "resource-1")).thenReturn(new ChatResourceService.ResourceResponse(
                new ResourceContent(new ByteArrayInputStream(new byte[]{1, 2, 3}), "image/png", 3L),
                "generated.png",
                false
        ));

        var response = controller.preview(principal, "resource-1", null);

        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals(3L, response.getHeaders().getContentLength());
        assertInstanceOf(InputStreamResource.class, response.getBody());
    }

    @Test
    void shouldReturnDownloadResourceWithAttachmentHeader() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(chatResourceService.openDownload(1L, "resource-1")).thenReturn(new ChatResourceService.ResourceResponse(
                new ResourceContent(new ByteArrayInputStream(new byte[]{1, 2, 3}), "image/png", 3L),
                "generated.png",
                true
        ));

        var response = controller.download(principal, "resource-1");

        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("attachment"));
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("generated.png"));
    }

    @Test
    void shouldRejectResourceOwnedByAnotherUser() {
        ChatMessageResourceMapper resourceMapper = mock(ChatMessageResourceMapper.class);
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ChatResourceService service = new ChatResourceServiceImpl(resourceMapper, resourceStorage);
        ChatMessageResourceEntity row = new ChatMessageResourceEntity();
        row.setId("resource-1");
        row.setUserId(2L);
        when(resourceMapper.selectByResourceId("resource-1")).thenReturn(row);

        BusinessException error = assertThrows(BusinessException.class, () -> service.openPreview(1L, "resource-1"));

        assertEquals(40404, error.getCode());
    }

    @Test
    void shouldReturnNotFoundBusinessErrorWhenCleanedResourceFileIsRequested() {
        ChatMessageResourceMapper resourceMapper = mock(ChatMessageResourceMapper.class);
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ChatResourceService service = new ChatResourceServiceImpl(resourceMapper, resourceStorage);
        ChatMessageResourceEntity row = new ChatMessageResourceEntity();
        row.setId("resource-1");
        row.setUserId(1L);
        row.setStorageKey("generated-videos/2026/07/14/resource-1.mp4");
        row.setFileName("video.mp4");
        when(resourceMapper.selectByResourceId("resource-1")).thenReturn(row);
        when(resourceStorage.open(row.getStorageKey()))
                .thenThrow(new IllegalStateException("Failed to open generated resource", new NoSuchFileException(row.getStorageKey())));

        BusinessException error = assertThrows(BusinessException.class, () -> service.openPreview(1L, "resource-1"));

        assertEquals(40404, error.getCode());
        assertEquals("资源文件已被清理", error.getMessage());
    }

    @Test
    void uploadImage_shouldSaveUnboundResourceAndReturnResponse() throws IOException {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(resourceStorage.save(any(ResourceSaveCommand.class))).thenReturn(
                new StoredResource("r-1", "LOCAL_FILE", "key1", "image/jpeg", "photo.jpg", 1024L, 100, 100)
        );
        when(resourceStorage.buildViewUrl("r-1")).thenReturn("/api/chat/resources/r-1/content");
        when(resourceStorage.buildDownloadUrl("r-1")).thenReturn("/api/chat/resources/r-1/download");

        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[1024]);
        var response = controller.upload(principal, file, "session-1", "REFERENCE");

        assertNotNull(response.getBody());
        assertEquals("r-1", response.getBody().resourceId());
        assertEquals("IMAGE", response.getBody().type());
        assertEquals("REFERENCE", response.getBody().role());
        assertEquals("photo.jpg", response.getBody().fileName());
        ArgumentCaptor<ChatMessageResourceEntity> captor = ArgumentCaptor.forClass(ChatMessageResourceEntity.class);
        verify(chatMessageResourceMapper).insert(captor.capture());
        ChatMessageResourceEntity row = captor.getValue();
        assertEquals("r-1", row.getId());
        assertNull(row.getMessageId());
        assertEquals(1L, row.getUserId());
        assertEquals("session-1", row.getSessionId());
        assertEquals("IMAGE", row.getResourceType());
        assertEquals("REFERENCE", row.getResourceRole());
        assertEquals("key1", row.getStorageKey());
        assertEquals("photo.jpg", row.getFileName());
    }

    @Test
    void upload_disallowedMimeType_shouldThrow() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[100]);

        BusinessException error = assertThrows(BusinessException.class, () -> controller.upload(principal, file, "session-1", "ATTACHMENT"));
        assertEquals(40000, error.getCode());
        assertTrue(error.getMessage().contains("暂不支持该文件类型"));
    }

    @Test
    void upload_fileTooLarge_shouldThrow() throws IOException {
        uploadProperties.setMaxFileSize(100L);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        MockMultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg", new byte[200]);

        BusinessException error = assertThrows(BusinessException.class, () -> controller.upload(principal, file, "session-1", "ATTACHMENT"));
        assertEquals(40000, error.getCode());
        assertTrue(error.getMessage().contains("文件大小不能超过"));
    }
}
