package com.h.backend.chat;

import com.h.backend.chat.application.ChatResourceUrls;
import com.h.backend.chat.application.ResourceContentPolicy;
import com.h.backend.chat.infrastructure.config.ResourceUploadProperties;
import com.h.backend.chat.infrastructure.content.ResourceContentInspector;
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
import com.h.backend.common.exception.GlobalExceptionHandler;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * 内容接口 HTTP 语义测试（新计划 §6.4/§11.3 / §10 任务 4）。
 *
 * <p>覆盖：200/206/400/416 完整 Range 语义（含 suffix、开放结尾、越界、多区间）、
 * nosniff 全响应、非白名单强制 attachment、Content-Type 用策略输出（未知→octet-stream）、
 * owner 鉴权先于对象读取（verify 顺序 + never open）、A 用户访问 B 资源不存在语义。
 */
class ChatResourceControllerTest {

    private final ChatResourceService chatResourceService = mock(ChatResourceService.class);
    private final ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
    private final ChatMessageResourceMapper chatMessageResourceMapper = mock(ChatMessageResourceMapper.class);
    private final ResourceUploadProperties uploadProperties = new ResourceUploadProperties();
    private final ChatResourceUrls chatResourceUrls = new ChatResourceUrls("");
    private final ResourceContentInspector contentInspector = new ResourceContentInspector();
    private final ResourceContentPolicy contentPolicy = new ResourceContentPolicy();
    private final ChatResourceController controller = new ChatResourceController(
            chatResourceService, writeCoordinator, chatMessageResourceMapper,
            uploadProperties, chatResourceUrls, contentInspector, contentPolicy
    );

    @Test
    void shouldReturnPreviewResourceWithImageContentType() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(chatResourceService.openPreview(eq(1L), eq("resource-1"), any(ResourceRange.class))).thenReturn(
                new ChatResourceService.ResourceResponse(
                        new ResourceContent(new ByteArrayInputStream(new byte[]{1, 2, 3}), "image/png", 3L, 3L, 0L, false),
                        "generated.png",
                        false,
                        "image/png"
                )
        );

        var response = controller.preview(principal, "resource-1", null);

        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertEquals(3L, response.getHeaders().getContentLength());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("bytes", response.getHeaders().getFirst(HttpHeaders.ACCEPT_RANGES));
        assertInstanceOf(InputStreamResource.class, response.getBody());
    }

    @Test
    void shouldReturnPartialContentForResourceRangeRequest() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(chatResourceService.openPreview(eq(1L), eq("resource-1"), eq(ResourceRange.fromHeader("bytes=1-2"))))
                .thenReturn(new ChatResourceService.ResourceResponse(
                        new ResourceContent(new ByteArrayInputStream(new byte[]{2, 3}), "video/mp4", 3L, 2L, 1L, true),
                        "video.mp4",
                        false,
                        "video/mp4"
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
    void shouldReturn206ForSuffixRangeRequest() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        // suffix Range bytes=-2：存储层解析为 offset=1/length=2 的尾部区间（206）
        when(chatResourceService.openPreview(eq(1L), eq("resource-1"), eq(ResourceRange.fromHeader("bytes=-2"))))
                .thenReturn(new ChatResourceService.ResourceResponse(
                        new ResourceContent(new ByteArrayInputStream(new byte[]{2, 3}), "video/mp4", 3L, 2L, 1L, true),
                        "video.mp4",
                        false,
                        "video/mp4"
                ));

        var response = controller.preview(principal, "resource-1", "bytes=-2");

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 1-2/3", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals(2L, response.getHeaders().getContentLength());
    }

    @Test
    void shouldReturn206ForOpenEndedRangeRequest() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        // 开放结尾 bytes=1-：截断到文件末尾 offset=1/length=2（206）
        when(chatResourceService.openPreview(eq(1L), eq("resource-1"), eq(ResourceRange.fromHeader("bytes=1-"))))
                .thenReturn(new ChatResourceService.ResourceResponse(
                        new ResourceContent(new ByteArrayInputStream(new byte[]{2, 3}), "video/mp4", 3L, 2L, 1L, true),
                        "video.mp4",
                        false,
                        "video/mp4"
                ));

        var response = controller.preview(principal, "resource-1", "bytes=1-");

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("bytes 1-2/3", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
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
    void shouldReturn416WithContentRangeForUnsatisfiableRange() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(chatResourceService.openPreview(eq(1L), eq("resource-1"), any(ResourceRange.class)))
                .thenThrow(ResourceRangeException.unsatisfiable(3L));

        // 新计划 §6.4 完整语义：416 + Content-Range: bytes */total
        var response = controller.preview(principal, "resource-1", "bytes=100-");

        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
        assertEquals("bytes */3", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        // 审查修复 7a：416 错误响应同样携带 nosniff，防止浏览器嗅探响应体
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertNull(response.getBody());
    }

    @Test
    void shouldReturn416WhenStartBeyondTotalSize() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(chatResourceService.openPreview(eq(1L), eq("resource-1"), any(ResourceRange.class)))
                .thenThrow(ResourceRangeException.unsatisfiable(1024L));

        var response = controller.preview(principal, "resource-1", "bytes=2000-3000");

        assertEquals(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE, response.getStatusCode());
        assertEquals("bytes */1024", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
    }

    @Test
    void shouldReturnDownloadResourceWithAttachmentHeader() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(chatResourceService.openDownload(1L, "resource-1")).thenReturn(new ChatResourceService.ResourceResponse(
                new ResourceContent(new ByteArrayInputStream(new byte[]{1, 2, 3}), "image/png", 3L, 3L, 0L, false),
                "generated.png",
                true,
                "image/png"
        ));

        var response = controller.download(principal, "resource-1");

        assertEquals(MediaType.IMAGE_PNG, response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("attachment"));
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("generated.png"));
        // download 响应同样必须带 nosniff（计划 §6.3：所有资源响应）
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
    }

    @Test
    void shouldForceAttachmentOnContentEndpointForNonPreviewableResource() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        // 非白名单（PDF）资源即使请求 /content 也强制 attachment（计划不变量 16）
        when(chatResourceService.openPreview(eq(1L), eq("resource-1"), any(ResourceRange.class))).thenReturn(
                new ChatResourceService.ResourceResponse(
                        new ResourceContent(new ByteArrayInputStream(new byte[]{1}), "application/pdf", 1L, 1L, 0L, false),
                        "report.pdf",
                        true,
                        "application/pdf"
                )
        );

        var response = controller.preview(principal, "resource-1", null);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("attachment"));
        assertTrue(response.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION).contains("report.pdf"));
    }

    @Test
    void shouldUsePolicyContentTypeInsteadOfStoredMimeType() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        // 存储侧 Content-Type 未知（octet-stream）时响应使用策略输出的 responseContentType
        when(chatResourceService.openDownload(1L, "resource-1")).thenReturn(new ChatResourceService.ResourceResponse(
                new ResourceContent(new ByteArrayInputStream(new byte[]{1}), "application/octet-stream", 1L, 1L, 0L, false),
                "report.pdf",
                true,
                "application/pdf"
        ));

        var response = controller.download(principal, "resource-1");

        assertEquals(MediaType.APPLICATION_PDF, response.getHeaders().getContentType(),
                "Content-Type 必须使用策略输出而非存储元数据");
    }

    @Test
    void shouldAddNosniffToPartialContentResponses() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(chatResourceService.openPreview(eq(1L), eq("resource-1"), eq(ResourceRange.fromHeader("bytes=0-1"))))
                .thenReturn(new ChatResourceService.ResourceResponse(
                        new ResourceContent(new ByteArrayInputStream(new byte[]{1, 2}), "video/mp4", 4L, 2L, 0L, true),
                        "video.mp4",
                        false,
                        "video/mp4"
                ));

        var response = controller.preview(principal, "resource-1", "bytes=0-1");

        assertEquals(HttpStatus.PARTIAL_CONTENT, response.getStatusCode());
        assertEquals("nosniff", response.getHeaders().getFirst("X-Content-Type-Options"));
        assertEquals("bytes 0-1/4", response.getHeaders().getFirst(HttpHeaders.CONTENT_RANGE));
    }

    @Test
    void shouldRejectResourceOwnedByAnotherUser() {
        ChatMessageResourceMapper resourceMapper = mock(ChatMessageResourceMapper.class);
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ResourceContentPolicy policy = new ResourceContentPolicy();
        ChatResourceService service = new ChatResourceServiceImpl(resourceMapper, resourceStorage, policy);
        ChatMessageResourceEntity row = new ChatMessageResourceEntity();
        row.setId("resource-1");
        row.setUserId(2L);
        when(resourceMapper.selectByResourceId("resource-1")).thenReturn(row);

        BusinessException error = assertThrows(
                BusinessException.class,
                () -> service.openPreview(1L, "resource-1", ResourceRange.fullRead())
        );

        // 新计划 §11.3：用户 A 访问用户 B 资源统一返回不存在；
        // owner 鉴权先于对象读取——存储层从未被触碰。
        assertEquals(40404, error.getCode());
        verifyNoInteractions(resourceStorage);
    }

    @Test
    void shouldReturnNotFoundBusinessErrorWhenCleanedResourceFileIsRequested() {
        ChatMessageResourceMapper resourceMapper = mock(ChatMessageResourceMapper.class);
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ResourceContentPolicy policy = new ResourceContentPolicy();
        ChatResourceService service = new ChatResourceServiceImpl(resourceMapper, resourceStorage, policy);
        ChatMessageResourceEntity row = new ChatMessageResourceEntity();
        row.setId("resource-1");
        row.setUserId(1L);
        row.setStorageType("OBJECT_STORAGE");
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
        // 审查修复第 1 项：Service 仍原样上抛 RSE（不做 HTTP 语义转换），
        // 四类存储错误的 HTTP 映射由 GlobalExceptionHandler 接管——
        // 预览接口对 UNAVAILABLE 返回 503、下载接口对 SIZE_LIMIT 返回 413。
        // 按现有直调风格模拟完整链路：Controller 直调捕获异常后交 handler。
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(chatResourceService.openPreview(eq(1L), eq("resource-1"), any(ResourceRange.class)))
                .thenThrow(new ResourceStorageException(ResourceStorageErrorKind.UNAVAILABLE, "资源存储暂时不可用"));
        when(chatResourceService.openDownload(eq(1L), eq("resource-1")))
                .thenThrow(new ResourceStorageException(ResourceStorageErrorKind.SIZE_LIMIT, "资源大小超过存储上限"));
        GlobalExceptionHandler exceptionHandler = new GlobalExceptionHandler();

        ResourceStorageException previewError = assertThrows(
                ResourceStorageException.class,
                () -> controller.preview(principal, "resource-1", null)
        );
        assertEquals(ResourceStorageErrorKind.UNAVAILABLE, previewError.kind());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE,
                exceptionHandler.handleResourceStorageException(previewError).getStatusCode());

        ResourceStorageException downloadError = assertThrows(
                ResourceStorageException.class,
                () -> controller.download(principal, "resource-1")
        );
        assertEquals(ResourceStorageErrorKind.SIZE_LIMIT, downloadError.kind());
        assertEquals(413, exceptionHandler.handleResourceStorageException(downloadError)
                .getStatusCode().value());
    }

    @Test
    void openPreviewFailsClosedWhenStorageTypeIsNotObjectStorage() {
        // 计划 §4.4：只服务 OBJECT_STORAGE 行；读到其他存储类型（历史/污染数据）
        // 按内部数据错误 fail closed（IO_ERROR→全局映射 500），消息不暴露
        // storage key，且存储层从未被触碰。
        ChatMessageResourceMapper resourceMapper = mock(ChatMessageResourceMapper.class);
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ChatResourceService service = new ChatResourceServiceImpl(
                resourceMapper, resourceStorage, new ResourceContentPolicy());
        ChatMessageResourceEntity row = ownedRow("image/png");
        row.setStorageType("LOCAL_DISK");
        row.setStorageKey("/var/local/disk/path/file.bin");
        when(resourceMapper.selectByResourceId("resource-1")).thenReturn(row);

        ResourceStorageException error = assertThrows(
                ResourceStorageException.class,
                () -> service.openPreview(1L, "resource-1", ResourceRange.fullRead())
        );

        assertEquals(ResourceStorageErrorKind.IO_ERROR, error.kind());
        assertFalse(error.getMessage().contains("/var/local"), "消息不得暴露 storage key");
        verifyNoInteractions(resourceStorage);
    }

    @Test
    void openDownloadFailsClosedWhenStorageTypeIsNotObjectStorage() {
        ChatMessageResourceMapper resourceMapper = mock(ChatMessageResourceMapper.class);
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ChatResourceService service = new ChatResourceServiceImpl(
                resourceMapper, resourceStorage, new ResourceContentPolicy());
        ChatMessageResourceEntity row = ownedRow("image/png");
        row.setStorageType(null);
        when(resourceMapper.selectByResourceId("resource-1")).thenReturn(row);

        assertThrows(ResourceStorageException.class,
                () -> service.openDownload(1L, "resource-1"));
        verifyNoInteractions(resourceStorage);
    }

    @Test
    void uploadClosesMultipartStreamWhenInspectionFails() throws IOException {
        // 审查修复第 4 项（同构）：inspect 抛 IOException 时必须安全关闭
        // MultipartFile 底层流，避免 fd 泄漏。
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        TrackingInputStream failingStream = new TrackingInputStream(new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("disk unreadable");
            }
        });
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", new byte[0]) {
            @Override
            public InputStream getInputStream() throws IOException {
                return failingStream;
            }
        };

        assertThrows(IOException.class, () -> controller.upload(principal, file, "ATTACHMENT"));

        assertTrue(failingStream.isClosed(), "inspect 失败后底层流必须被关闭");
        verifyNoInteractions(writeCoordinator);
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

        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes(1024));
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
        MockMultipartFile file = spy(new MockMultipartFile(
                "file", "photo.jpg", "image/jpeg", jpegBytes(8)));

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
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", jpegBytes(8));

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

    // ------------------------------------------------------------------
    // 上传内容安全（计划 §6.3 / §11.3：签名校验 + 主动内容拒绝 + 回放流）
    // ------------------------------------------------------------------

    @Test
    void upload_forgedSignature_shouldReject() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        // 声明 image/jpeg 但字节全零：白名单类型未通过签名校验
        MockMultipartFile file = new MockMultipartFile("file", "fake.jpg", "image/jpeg", new byte[64]);

        BusinessException error = assertThrows(
                BusinessException.class, () -> controller.upload(principal, file, "ATTACHMENT"));
        assertEquals(40000, error.getCode());
        assertTrue(error.getMessage().contains("签名"));
        verify(writeCoordinator, never()).saveAndAttach(any(), any());
    }

    @Test
    void upload_signatureMismatchWithDeclaredMime_shouldReject() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        // 声明 PNG 但字节实际是 JPEG：模型/用户声明不能覆盖检测（拒绝方案 10）
        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "image/png", jpegBytes());

        BusinessException error = assertThrows(
                BusinessException.class, () -> controller.upload(principal, file, "ATTACHMENT"));
        assertEquals(40000, error.getCode());
        assertTrue(error.getMessage().contains("签名"));
        verify(writeCoordinator, never()).saveAndAttach(any(), any());
    }

    @Test
    void upload_activeContent_shouldReject() {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        // 声明白名单 MIME 但内容是 HTML：主动内容明确拒绝（计划 §6.3）
        MockMultipartFile file = new MockMultipartFile(
                "file", "evil.png", "image/png",
                "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8));

        BusinessException error = assertThrows(
                BusinessException.class, () -> controller.upload(principal, file, "ATTACHMENT"));
        assertEquals(40000, error.getCode());
        assertTrue(error.getMessage().contains("主动内容"));
        verify(writeCoordinator, never()).saveAndAttach(any(), any());
    }

    @Test
    void upload_validJpeg_replayStreamPreservesFullContent() throws IOException {
        AuthUserPrincipal principal = new AuthUserPrincipal(1L, "user@example.com", "USER");
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<com.h.backend.chat.interfaces.dto.ResourceUploadResponse> attachment =
                            invocation.getArgument(1);
                    return attachment.attach(
                            new StoredResource("r-4", "OBJECT_STORAGE", "key4", "image/jpeg", "photo.jpg", 300L, null, null)
                    );
                });
        // 大于头缓冲上限的 JPEG：校验读过的头字节必须全部回放进保存流（不丢数据、不二次读源）
        byte[] payload = jpegBytes(700);
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "image/jpeg", payload);

        controller.upload(principal, file, "ATTACHMENT");

        ArgumentCaptor<ResourceSaveCommand> commandCaptor = ArgumentCaptor.forClass(ResourceSaveCommand.class);
        verify(writeCoordinator).saveAndAttach(commandCaptor.capture(), any());
        try (var in = commandCaptor.getValue().openContentStream()) {
            assertArrayEquals(payload, in.readAllBytes(),
                    "保存流必须完整回放原文件字节（含已用于签名校验的头字节）");
        }
    }

    /** JPEG 魔数 + 填充字节。 */
    private static byte[] jpegBytes(int size) {
        byte[] payload = new byte[size];
        payload[0] = (byte) 0xFF;
        payload[1] = (byte) 0xD8;
        payload[2] = (byte) 0xFF;
        payload[3] = (byte) 0xE0;
        for (int i = 4; i < size; i++) {
            payload[i] = (byte) (i % 251);
        }
        return payload;
    }

    private static byte[] jpegBytes() {
        return jpegBytes(32);
    }

    // ------------------------------------------------------------------
    // Service 层：inline 白名单 / attachment 强制 / 未知 octet-stream（计划 §6.3）
    // ------------------------------------------------------------------

    @Test
    void previewAllowsInlineOnlyForWhitelistedMimeTypes() {
        ChatResourceService service = serviceWithOwnedResource("video/mp4");

        ChatResourceService.ResourceResponse response =
                service.openPreview(1L, "resource-1", ResourceRange.fullRead());

        assertEquals(false, response.attachment(), "白名单音视频允许 inline 预览");
        assertEquals("video/mp4", response.responseContentType());
    }

    @Test
    void previewForcesAttachmentForNonPreviewableMimeTypes() {
        // PDF 与 SVG（历史/污染数据）即使请求 /content 也强制 attachment
        assertEquals(true, serviceWithOwnedResource("application/pdf")
                .openPreview(1L, "resource-1", ResourceRange.fullRead()).attachment());
        assertEquals(true, serviceWithOwnedResource("image/svg+xml")
                .openPreview(1L, "resource-1", ResourceRange.fullRead()).attachment());
    }

    @Test
    void previewFallsBackToOctetStreamForUnknownStoredMime() {
        ChatResourceService service = serviceWithOwnedResource(null);

        ChatResourceService.ResourceResponse response =
                service.openPreview(1L, "resource-1", ResourceRange.fullRead());

        assertEquals(true, response.attachment());
        assertEquals("application/octet-stream", response.responseContentType(),
                "未知类型响应 application/octet-stream（计划 §6.3）");
    }

    @Test
    void downloadIsAlwaysAttachmentEvenForInlinePreviewableMime() {
        ChatResourceService service = serviceWithOwnedResource("image/png");

        ChatResourceService.ResourceResponse response = service.openDownload(1L, "resource-1");

        assertEquals(true, response.attachment(), "download 恒 attachment");
        assertEquals("image/png", response.responseContentType());
    }

    @Test
    void rangeRequestIsForwardedToStorageAndResponseCarriesOnlyRangeStream() throws IOException {
        // 证明（mock 层面）：Range 请求把对应 ResourceRange 下推给 open，
        // 响应体正是 open 返回的区间流，不下载完整对象（计划 §6.4/任务 4）
        ChatMessageResourceMapper resourceMapper = mock(ChatMessageResourceMapper.class);
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ChatResourceService service = new ChatResourceServiceImpl(
                resourceMapper, resourceStorage, new ResourceContentPolicy());
        ChatMessageResourceEntity row = ownedRow("video/mp4");
        when(resourceMapper.selectByResourceId("resource-1")).thenReturn(row);
        InputStream rangeStream = new ByteArrayInputStream(new byte[]{2, 3});
        when(resourceStorage.open(eq("key-1"), any(ResourceRange.class)))
                .thenReturn(new ResourceContent(rangeStream, "video/mp4", 3L, 2L, 1L, true));

        ChatResourceService.ResourceResponse response =
                service.openPreview(1L, "resource-1", ResourceRange.fromHeader("bytes=-2"));

        ArgumentCaptor<ResourceRange> rangeCaptor = ArgumentCaptor.forClass(ResourceRange.class);
        verify(resourceStorage).open(eq("key-1"), rangeCaptor.capture());
        assertEquals(ResourceRange.Kind.SUFFIX, rangeCaptor.getValue().kind(),
                "suffix Range 语法解析结果必须原样下推给存储层");
        assertEquals(2L, rangeCaptor.getValue().suffixLength());
        assertSame(rangeStream, response.content().inputStream(),
                "响应体必须是存储层返回的区间流，而非重新读完整对象");
        assertEquals(2L, response.content().responseLength());
        assertTrue(response.content().partial());
    }

    @Test
    void ownerCheckRunsBeforeStorageOpen() {
        // owner 鉴权先于对象读取（计划 §11.3）：合法请求也必须先查库鉴权再 open
        ChatMessageResourceMapper resourceMapper = mock(ChatMessageResourceMapper.class);
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ChatResourceService service = new ChatResourceServiceImpl(
                resourceMapper, resourceStorage, new ResourceContentPolicy());
        ChatMessageResourceEntity row = ownedRow("image/png");
        when(resourceMapper.selectByResourceId("resource-1")).thenReturn(row);
        when(resourceStorage.open(any(String.class), any(ResourceRange.class))).thenReturn(
                new ResourceContent(new ByteArrayInputStream(new byte[1]), "image/png", 1L, 1L, 0L, false));

        service.openPreview(1L, "resource-1", ResourceRange.fullRead());

        var order = inOrder(resourceMapper, resourceStorage);
        order.verify(resourceMapper).selectByResourceId("resource-1");
        order.verify(resourceStorage).open(eq("key-1"), any(ResourceRange.class));
    }

    private ChatResourceService serviceWithOwnedResource(String storedMimeType) {
        ChatMessageResourceMapper resourceMapper = mock(ChatMessageResourceMapper.class);
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ChatResourceService service = new ChatResourceServiceImpl(
                resourceMapper, resourceStorage, new ResourceContentPolicy());
        ChatMessageResourceEntity row = ownedRow(storedMimeType);
        when(resourceMapper.selectByResourceId("resource-1")).thenReturn(row);
        when(resourceStorage.open(eq("key-1"), any(ResourceRange.class))).thenReturn(
                new ResourceContent(new ByteArrayInputStream(new byte[1]), storedMimeType, 1L, 1L, 0L, false));
        return service;
    }

    private ChatMessageResourceEntity ownedRow(String storedMimeType) {
        ChatMessageResourceEntity row = new ChatMessageResourceEntity();
        row.setId("resource-1");
        row.setUserId(1L);
        row.setStorageType("OBJECT_STORAGE");
        row.setStorageKey("key-1");
        row.setFileName("generated.png");
        row.setMimeType(storedMimeType);
        return row;
    }

    /** 跟踪关闭状态的包装流（fd 泄漏断言用）。 */
    private static final class TrackingInputStream extends InputStream {
        private final InputStream delegate;
        private boolean closed;

        TrackingInputStream(InputStream delegate) {
            this.delegate = delegate;
        }

        boolean isClosed() {
            return closed;
        }

        @Override
        public int read() throws IOException {
            return delegate.read();
        }

        @Override
        public void close() throws IOException {
            closed = true;
            delegate.close();
        }
    }
}
