package com.h.backend.chat;

import com.h.backend.chat.controller.ChatResourceController;
import com.h.backend.chat.entity.ChatMessageResourceEntity;
import com.h.backend.chat.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.service.ChatResourceService;
import com.h.backend.chat.service.impl.ChatResourceServiceImpl;
import com.h.backend.chat.storage.ResourceContent;
import com.h.backend.chat.storage.ResourceStorage;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.security.AuthUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ChatResourceControllerTest {

    @Test
    void shouldReturnPreviewResourceWithImageContentType() {
        ChatResourceService chatResourceService = mock(ChatResourceService.class);
        ChatResourceController controller = new ChatResourceController(chatResourceService);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(chatResourceService.openPreview(1L, "resource-1")).thenReturn(new ChatResourceService.ResourceResponse(
                new ResourceContent(new ByteArrayInputStream(new byte[]{1, 2, 3}), "image/png", 3L),
                "generated.png",
                false
        ));

        var response = controller.preview(principal, "resource-1");

        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals(3L, response.getHeaders().getContentLength());
        assertInstanceOf(InputStreamResource.class, response.getBody());
    }

    @Test
    void shouldReturnDownloadResourceWithAttachmentHeader() {
        ChatResourceService chatResourceService = mock(ChatResourceService.class);
        ChatResourceController controller = new ChatResourceController(chatResourceService);
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
}
