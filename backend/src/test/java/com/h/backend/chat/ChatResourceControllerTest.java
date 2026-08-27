package com.h.backend.chat;

import com.h.backend.chat.application.ChatResourceUrls;
import com.h.backend.chat.infrastructure.config.ResourceUploadProperties;
import com.h.backend.chat.interfaces.web.ChatResourceController;
import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.application.ChatResourceService;
import com.h.backend.chat.application.impl.ChatResourceServiceImpl;
import com.h.backend.chat.infrastructure.storage.ResourceAttachment;
import com.h.backend.chat.infrastructure.storage.ResourceContent;
import com.h.backend.chat.infrastructure.storage.ResourceRange;
import com.h.backend.chat.infrastructure.storage.ResourceRangeException;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceStorage;
import com.h.backend.chat.infrastructure.storage.ResourceStorageErrorKind;
import com.h.backend.chat.infrastructure.storage.ResourceStorageException;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class ChatResourceControllerTest {

    private final ChatResourceService chatResourceService = mock(ChatResourceService.class);
    private final ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
    private final ChatMessageResourceMapper chatMessageResourceMapper = mock(ChatMessageResourceMapper.class);
    private final ResourceUploadProperties uploadProperties = new ResourceUploadProperties();
    private final ChatResourceUrls chatResourceUrls = new ChatResourceUrls("");
    private final ChatResourceController controller = new ChatResourceController(
            chatResourceService, writeCoordinator, chatMessageResourceMapper, uploadProperties, chatResourceUrls
    );

    @Test
    void shouldReturnPreviewResourceWithImageContentType() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(chatResourceService.openPreview(eq(1L), eq("resource-1"), any(ResourceRange.class))).thenReturn(
                new ChatResourceService.ResourceResponse(
                        new ResourceContent(new ByteArrayInputStream(new byte[]{1, 2, 3}), "image/png", 3L, 3L, 0L, false),
                        "generated.png",
                        false
                )
        );

        var response = controller.preview(principal, "resource-1", null);

        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals(3L, response.getHeaders().getContentLength());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertInstanceOf(InputStreamResource.class, response.getBody());
    }

    @Test
    void shouldReturnPartialContentForResourceRangeRequest() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(chatResourceService.openPreview(eq(1L), eq("resource-1"), eq(ResourceRange.fromHeader("bytes=1-2"))))
                .thenReturn(new ChatResourceService.ResourceResponse(
                        new ResourceContent(new ByteArrayInputStream(new byte[]{2, 3}), "video/mp4", 3L, 2L, 1L, true),
                        "video.mp4",
                        false
                ));

        var response = controller.preview(principal, "resource-1", "bytes=1-2");

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals(MediaType.valueOf("video/mp4"), response.getHeaders().getContentType());
        assertEquals(2L, response.getHeaders().getContentLength());
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
        assertEquals("bytes 1-2/3", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertInstanceOf(InputStreamResource.class, response.getBody());
    }

    @Test
    void shouldRejectMalformedRangeHeaderWithBadRequest() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> controller.preview(principal, "resource-1", "bytes=0-1,5-6")
        );

        assertEquals(40000, error.getCode());
    }

    @Test
    void shouldRejectUnsatisfiableRangeHeaderUntilTask4AddsFull416Semantics() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(chatResourceService.openPreview(eq(1L), eq("resource-1"), any(ResourceRange.class)))
                .thenThrow(ResourceRangeException.unsatisfiable(3L));

        // 任务 1 过渡语义：416 + Content-Range: bytes */total 留给任务 4 实现。
        BusinessException error = assertThrows(
                BusinessException.class,
                () -> controller.preview(principal, "resource-1", "bytes=100-")
        );

        assertEquals(40000, error.getCode());
        assertTrue(error.getMessage().contains("无法满足"));
    }

    @Test
    void shouldReturnDownloadResourceWithAttachmentHeader() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(chatResourceService.openDownload(1L, "resource-1")).thenReturn(new ChatResourceService.ResourceResponse(
                new ResourceContent(new ByteArrayInputStream(new byte[]{1, 2, 3}), "image/png", 3L, 3L, 0L, false),
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

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.openPreview(1L, "resource-1", ResourceRange.fullRead())
        );

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
        when(resourceStorage.open(eq(row.getStorageKey()), any(ResourceRange.class)))
                .thenThrow(new ResourceStorageException(ResourceStorageErrorKind.NOT_FOUND, "资源不存在或已被清理"));

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.openPreview(1L, "resource-1", ResourceRange.fullRead())
        );

        assertEquals(40404, error.getCode());
        assertEquals("资源文件已被清理", error.getMessage());
    }

    @Test
    void shouldRethrowOtherStorageErrorKindsFromPreview() {
        ChatMessageResourceMapper resourceMapper = mock(ChatMessageResourceMapper.class);
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ChatResourceService service = new ChatResourceServiceImpl(resourceMapper, resourceStorage);
        ChatMessageResourceEntity row = new ChatMessageResourceEntity();
        row.setId("resource-1");
        row.setUserId(1L);
        row.setStorageKey("generated-videos/2026/07/14/resource-1.mp4");
        when(resourceMapper.selectByResourceId("resource-1")).thenReturn(row);
        when(resourceStorage.open(eq(row.getStorageKey()), any(ResourceRange.class)))
                .thenThrow(new ResourceStorageException(ResourceStorageErrorKind.UNAVAILABLE, "资源存储暂时不可用"));

        ResourceStorageException error = assertThrows(
                ResourceStorageException.class,
                () -> service.openPreview(1L, "resource-1", ResourceRange.fullRead())
        );

        assertEquals(ResourceStorageErrorKind.UNAVAILABLE, error.kind());
    }

    @Test
    void uploadImage_shouldSaveUnboundResourceAndReturnResponse() throws IOException {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        // mock 边界（任务 3）：Controller 测试 mock Coordinator；
        // 回调由 mock 同步执行，保持 attachment 内 DB 行为可断言。
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<com.h.backend.chat.interfaces.dto.ResourceUploadResponse> attachment =
                            invocation.getArgument(1);
                    return attachment.attach(
                            new StoredResource("r-1", "OBJECT_STORAGE", "key1", "image/jpeg", "photo.jpg", 1024L, 100, 100)
                    );
                });

        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[1024]);
        var response = controller.upload(principal, file, "REFERENCE");

        assertNotNull(response.getBody());
        assertEquals("r-1", response.getBody().resourceId());
        assertEquals("IMAGE", response.getBody().type());
        assertEquals("REFERENCE", response.getBody().role());
        assertEquals("photo.jpg", response.getBody().fileName());
        assertEquals("/api/chat/resources/r-1/content", response.getBody().viewUrl());
        assertEquals("/api/chat/resources/r-1/download", response.getBody().downloadUrl());
        ArgumentCaptor<ChatMessageResourceEntity> captor = ArgumentCaptor.forClass(ChatMessageResourceEntity.class);
        verify(chatMessageResourceMapper).insert(captor.capture());
        ChatMessageResourceEntity row = captor.getValue();
        assertEquals("r-1", row.getId());
        assertNull(row.getMessageId());
        assertEquals(1L, row.getUserId());
        assertEquals("IMAGE", row.getResourceType());
        assertEquals("REFERENCE", row.getResourceRole());
        assertEquals("OBJECT_STORAGE", row.getStorageType());
        assertEquals("key1", row.getStorageKey());
        assertEquals("/api/chat/resources/r-1/content", row.getViewUrl());
        assertEquals("/api/chat/resources/r-1/download", row.getDownloadUrl());
        assertEquals("photo.jpg", row.getFileName());

        // 写入命令必须是流式形态（计划不变量 10：上传不调 getBytes，不进 byte[]）
        ArgumentCaptor<ResourceSaveCommand> commandCaptor = ArgumentCaptor.forClass(ResourceSaveCommand.class);
        verify(writeCoordinator).saveAndAttach(commandCaptor.capture(), any());
        ResourceSaveCommand command = commandCaptor.getValue();
        assertEquals("IMAGE", command.resourceType());
        assertNull(command.content(), "上传必须走流式命令，不得装入 byte[]");
        assertEquals(1024L, command.declaredSize(), "MultipartFile 已知大小必须作为 declaredSize 传入");
        assertEquals("image/jpeg", command.mimeType());
        assertEquals("jpg", command.extension());
        assertEquals(uploadProperties.getMaxFileSize(), command.maxBytes(), "业务上限必须沿用上传配置");
    }

    @Test
    void upload_readsInputStreamAndNeverCallsGetBytes() throws IOException {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<com.h.backend.chat.interfaces.dto.ResourceUploadResponse> attachment =
                            invocation.getArgument(1);
                    return attachment.attach(
                            new StoredResource("r-2", "OBJECT_STORAGE", "key2", "image/jpeg", "photo.jpg", 8L, null, null)
                    );
                });
        MockMultipartFile file = spy(new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[8]));

        controller.upload(principal, file, "ATTACHMENT");

        // 计划 §6.1 / 拒绝方案 8：用户上传从 getInputStream() 读取，不调用 getBytes()
        verify(file).getInputStream();
        verify(file, never()).getBytes();
    }

    @Test
    void upload_attachmentFailurePropagatesThroughCoordinator() throws IOException {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        IllegalStateException boom = new IllegalStateException("数据库挂接失败");
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<Object> attachment = invocation.getArgument(1);
                    return attachment.attach(
                            new StoredResource("r-3", "OBJECT_STORAGE", "key3", "image/jpeg", "photo.jpg", 8L, null, null)
                    );
                });
        when(chatMessageResourceMapper.insert(any(ChatMessageResourceEntity.class))).thenThrow(boom);
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[8]);

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> controller.upload(principal, file, "ATTACHMENT"));

        assertSame(boom, thrown);
    }

    @Test
    void upload_disallowedMimeType_shouldThrow() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", new byte[100]);

        BusinessException error = assertThrows(BusinessException.class, () -> controller.upload(principal, file, "ATTACHMENT"));
        assertEquals(40000, error.getCode());
        assertTrue(error.getMessage().contains("暂不支持该文件类型"));
    }

    @Test
    void upload_fileTooLarge_shouldThrow() throws IOException {
        uploadProperties.setMaxFileSize(100L);
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        MockMultipartFile file = new MockMultipartFile("file", "big.jpg", "image/jpeg", new byte[200]);

        BusinessException error = assertThrows(BusinessException.class, () -> controller.upload(principal, file, "ATTACHMENT"));
        assertEquals(40000, error.getCode());
        assertTrue(error.getMessage().contains("文件大小不能超过"));
    }
}
