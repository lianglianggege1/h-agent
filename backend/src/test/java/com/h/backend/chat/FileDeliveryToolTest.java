package com.h.backend.chat;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatResourceUrls;
import com.h.backend.chat.application.ChatStreamEventBridge;
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

    @Test
    void shouldSendSessionFileToCurrentChatStream() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        ResourceWriteCoordinator writeCoordinator = mock(ResourceWriteCoordinator.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatStreamEventBridge bridge = new ChatStreamEventBridge();
        FileDeliveryTool tool = new FileDeliveryTool(
                assistantFileStorage, writeCoordinator, chatSessionService, bridge, new ChatResourceUrls(""));
        String memoryId = "1:22:session-1";
        assistantFileStorage.write(memoryId, "/deck.pptx", "ppt-bytes");

        // mock 边界（任务 3）：调用方测试 mock Coordinator，attachment 同步执行。
        when(writeCoordinator.saveAndAttach(any(ResourceSaveCommand.class), any()))
                .thenAnswer(invocation -> {
                    ResourceAttachment<ChatSessionMessageDto> attachment = invocation.getArgument(1);
                    return attachment.attach(new StoredResource(
                            "resource-1",
                            "LOCAL_FILE",
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
                "LOCAL_FILE",
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
        FileDeliveryTool tool = new FileDeliveryTool(
                assistantFileStorage, writeCoordinator, chatSessionService, bridge, new ChatResourceUrls(""));
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
        FileDeliveryTool tool = new FileDeliveryTool(
                assistantFileStorage, writeCoordinator, chatSessionService, bridge, new ChatResourceUrls(""));
        String memoryId = "1:22:session-1";
        assistantFileStorage.write(memoryId, "/deck.pptx", "ppt-bytes");

        String result = tool.sendFileToChat(memoryId, "/deck.pptx", "客户方案.pptx", null, null);

        assertEquals("Error: File exceeds max readable size: /deck.pptx", result);
        verify(writeCoordinator, never()).saveAndAttach(any(), any());
    }

    @Test
    void shouldRejectSendingFilesOutsideSessionDirectory() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        FileDeliveryTool tool = new FileDeliveryTool(
                assistantFileStorage,
                mock(ResourceWriteCoordinator.class),
                mock(ChatSessionService.class),
                new ChatStreamEventBridge(),
                new ChatResourceUrls("")
        );

        assertEquals(
                "Error: Path traversal is not allowed",
                tool.sendFileToChat("1:22:session-1", "/../secret.txt", "secret.txt", null, null)
        );
    }
}
