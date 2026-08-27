package com.h.backend.chat;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatResourceUrls;
import com.h.backend.chat.application.ChatStreamEventBridge;
import com.h.backend.chat.application.ResourceContentPolicy;
import com.h.backend.chat.infrastructure.content.ResourceContentInspector;
import com.h.backend.chat.infrastructure.filesystem.AssistantFileStorage;
import com.h.backend.chat.infrastructure.storage.ResourceAttachment;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.chat.infrastructure.tools.FileDeliveryTool;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileDeliveryToolTest {

    @TempDir
    Path tempDir;

    private final ResourceContentInspector contentInspector = new ResourceContentInspector();
    private final ResourceContentPolicy contentPolicy = new ResourceContentPolicy();

    private FileDeliveryTool newTool(
            AssistantFileStorage assistantFileStorage,
            ResourceWriteCoordinator writeCoordinator,
            ChatSessionService chatSessionService,
            ChatStreamEventBridge bridge
    ) {
        return new FileDeliveryTool(
                assistantFileStorage,
                writeCoordinator,
                chatSessionService,
                bridge,
                new ChatResourceUrls(""),
                contentInspector,
                contentPolicy
        );
    }

    @Test
    void shouldSendSessionFileToCurrentChatStream() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatStreamEventBridge bridge = new ChatStreamEventBridge();
        FileDeliveryTool tool = newTool(assistantFileStorage, writeCoordinator, chatSessionService, bridge);
        String memoryId = "1:22:session-1";
        assistantFileStorage.write(memoryId, "/deck.pptx", "ppt-bytes");

        // mock 边界（任务 3）：调用方测试 mock Coordinator，attachment 同步执行。
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<ChatSessionMessageDto> attachment = invocation.getArgument(1);
                    return attachment.attach(new StoredResource(
                            "resource-1",
                            "OBJECT_STORAGE",
                            "generated-files/2026/07/07/resource-1.pptx",
                            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                            "generated-resource-1.pptx",
                            9L,
                            null,
                            null
                    ));
                });

        ChatMessageResourceDto resourceDto = new ChatMessageResourceDto(
                "resource-1",
                "FILE",
                "GENERATED",
                "/api/chat/resources/resource-1/content",
                "/api/chat/resources/resource-1/download",
                "客户方案.pptx",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                9L,
                null,
                null,
                "OBJECT_STORAGE",
                "generated-files/2026/07/07/resource-1.pptx"
        );
        ChatSessionMessageDto message = new ChatSessionMessageDto(
                "501",
                "assistant",
                "FILE",
                "已发送文件：客户方案.pptx",
                null,
                List.of(resourceDto),
                LocalDateTime.now()
        );
        when(chatSessionService.appendResourceMessage(eq(1L), eq("session-1"), eq("已发送文件：客户方案.pptx"), eq("FILE"), eq(List.of(resourceDto))))
                .thenReturn(message);

        ArrayList<ChatSessionMessageDto> published = new ArrayList<>();
        String result = bridge.withPublisher(
                memoryId,
                published::add,
                () -> tool.sendFileToChat(memoryId, "/deck.pptx", "客户方案.pptx", null, null)
        );

        assertEquals("文件已发送到聊天中。", result);
        assertEquals(List.of(message), published);

        // 写入命令必须是流式形态：工作文件不再复制完整 byte array（计划任务 3）
        ArgumentCaptor<ResourceSaveCommand> saveCommand = ArgumentCaptor.forClass(ResourceSaveCommand.class);
        verify(writeCoordinator).saveAndAttach(saveCommand.capture(), any());
        assertEquals("FILE", saveCommand.getValue().resourceType());
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", saveCommand.getValue().mimeType());
        assertEquals("pptx", saveCommand.getValue().extension());
        assertNull(saveCommand.getValue().content(), "工作文件必须以流式进入命令，不得整读为 byte[]");
        assertEquals(9L, saveCommand.getValue().declaredSize(), "工作文件已知大小作为 declaredSize");
        verify(chatSessionService).appendResourceMessage(1L, "session-1", "已发送文件：客户方案.pptx", "FILE", List.of(resourceDto));
    }

    @Test
    void shouldPropagateAttachmentFailureAndSkipStreamEventPublish() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatStreamEventBridge bridge = new ChatStreamEventBridge();
        FileDeliveryTool tool = newTool(assistantFileStorage, writeCoordinator, chatSessionService, bridge);
        String memoryId = "1:22:session-1";
        assistantFileStorage.write(memoryId, "/deck.pptx", "ppt-bytes");

        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<ChatSessionMessageDto> attachment = invocation.getArgument(1);
                    return attachment.attach(new StoredResource(
                            "resource-1", "OBJECT_STORAGE", "key-1", "application/octet-stream", "deck.pptx", 9L, null, null));
                });
        IllegalStateException boom = new IllegalStateException("消息挂接失败");
        when(chatSessionService.appendResourceMessage(any(), any(), any(), any(), any())).thenThrow(boom);

        // 挂接失败必须原样上抛（Coordinator 事务 rollback 补偿对象），不得返回错误文案。
        ArrayList<ChatSessionMessageDto> published = new ArrayList<>();
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () ->
                bridge.withPublisher(
                        memoryId,
                        published::add,
                        () -> tool.sendFileToChat(memoryId, "/deck.pptx", "客户方案.pptx", null, null)
                ));

        assertSame(boom, thrown);
        assertTrue(published.isEmpty(), "挂接失败时流事件不得发布");
    }

    @Test
    void shouldRejectOversizedSessionFileWithoutTouchingCoordinator() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 8);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatStreamEventBridge bridge = new ChatStreamEventBridge();
        FileDeliveryTool tool = newTool(assistantFileStorage, writeCoordinator, chatSessionService, bridge);
        String memoryId = "1:22:session-1";
        assistantFileStorage.write(memoryId, "/deck.pptx", "ppt-bytes");

        String result = tool.sendFileToChat(memoryId, "/deck.pptx", "客户方案.pptx", null, null);

        assertEquals("Error: File exceeds max readable size: /deck.pptx", result);
        verify(writeCoordinator, never()).saveAndAttach(any(), any());
    }

    @Test
    void shouldRejectSendingFilesOutsideSessionDirectory() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        FileDeliveryTool tool = newTool(
                assistantFileStorage,
                mock(ResourceWriteCoordinator.class),
                mock(ChatSessionService.class),
                new ChatStreamEventBridge()
        );

        assertEquals(
                "Error: Path traversal is not allowed",
                tool.sendFileToChat("1:22:session-1", "/../secret.txt", "secret.txt", null, null)
        );
    }

    // ------------------------------------------------------------------
    // 内容安全（新计划 §6.3 / §10 任务 4）：Agent 模型文件属于不可信输入，
    // 保存前必须经签名校验；模型声明的 MIME 只是提示（拒绝方案 10）。
    // ------------------------------------------------------------------

    @Test
    void shouldRejectFileWhoseDeclaredMimeConflictsWithContent() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatStreamEventBridge bridge = new ChatStreamEventBridge();
        FileDeliveryTool tool = newTool(assistantFileStorage, writeCoordinator, mock(ChatSessionService.class), bridge);
        String memoryId = "1:22:session-1";
        // 模型声明 image/png，但文件内容是纯文本：签名校验拒绝
        assistantFileStorage.write(memoryId, "/fake.png", "plain text, definitely not a png");

        String result = tool.sendFileToChat(memoryId, "/fake.png", "fake.png", "image/png", null);

        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("签名"));
        verify(writeCoordinator, never()).saveAndAttach(any(), any());
    }

    @Test
    void shouldRejectActiveContentDelivery() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatStreamEventBridge bridge = new ChatStreamEventBridge();
        FileDeliveryTool tool = newTool(assistantFileStorage, writeCoordinator, mock(ChatSessionService.class), bridge);
        String memoryId = "1:22:session-1";
        // 伪装成图片的 HTML 主动内容：无论声明什么都拒绝（计划 §6.3）
        assistantFileStorage.write(memoryId, "/evil.png", "<html><script>alert(1)</script></html>");

        String result = tool.sendFileToChat(memoryId, "/evil.png", "evil.png", "image/png", null);

        assertTrue(result.startsWith("Error:"));
        assertTrue(result.contains("主动内容"));
        verify(writeCoordinator, never()).saveAndAttach(any(), any());
    }

    @Test
    void shouldDeliverRealPngFileWithMatchingDeclaredMime() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatStreamEventBridge bridge = new ChatStreamEventBridge();
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        FileDeliveryTool tool = newTool(assistantFileStorage, writeCoordinator, chatSessionService, bridge);
        String memoryId = "1:22:session-1";
        // 真实 PNG 魔数二进制内容：直接写入会话目录（write() 的字符串 API 会做
        // UTF-8 重编码破坏二进制），校验通过后保存流必须完整回放（不丢头字节）
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3, 4};
        try {
            Path sessionDir = tempDir.resolve("assistant-files").resolve("1").resolve("session-1");
            java.nio.file.Files.createDirectories(sessionDir);
            java.nio.file.Files.write(sessionDir.resolve("icon.png"), png);
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<ChatSessionMessageDto> attachment = invocation.getArgument(1);
                    return attachment.attach(new StoredResource(
                            "resource-9", "OBJECT_STORAGE", "key-9", "image/png", "icon.png", 12L, null, null));
                });
        when(chatSessionService.appendResourceMessage(any(), any(), any(), any(), any())).thenReturn(
                new ChatSessionMessageDto("m-1", "assistant", "IMAGE", "已发送文件：icon.png",
                        null, List.of(), LocalDateTime.now()));

        String result = tool.sendFileToChat(memoryId, "/icon.png", "icon.png", "image/png", null);

        assertEquals("文件已发送到聊天中。", result);
        ArgumentCaptor<ResourceSaveCommand> saveCommand = ArgumentCaptor.forClass(ResourceSaveCommand.class);
        verify(writeCoordinator).saveAndAttach(saveCommand.capture(), any());
        try (var in = saveCommand.getValue().openContentStream()) {
            assertArrayEquals(png, in.readAllBytes(), "保存流必须完整回放 PNG 内容（含已校验头字节）");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }
}
