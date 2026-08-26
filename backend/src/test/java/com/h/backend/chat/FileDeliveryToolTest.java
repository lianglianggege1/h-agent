package com.h.backend.chat;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatResourceUrls;
import com.h.backend.chat.application.ChatStreamEventBridge;
import com.h.backend.chat.infrastructure.filesystem.AssistantFileStorage;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceStorage;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class FileDeliveryToolTest {

    @TempDir
    Path tempDir;

    @Test
    void shouldSendSessionFileToCurrentChatStream() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        ResourceStorage resourceStorage = mock(ResourceStorage.class);
        ChatSessionService chatSessionService = mock(ChatSessionService.class);
        ChatStreamEventBridge bridge = new ChatStreamEventBridge();
        FileDeliveryTool tool = new FileDeliveryTool(
                assistantFileStorage, resourceStorage, chatSessionService, bridge, new ChatResourceUrls(""));
        String memoryId = "1:22:session-1";
        assistantFileStorage.write(memoryId, "/deck.pptx", "ppt-bytes");

        when(resourceStorage.save(org.mockito.ArgumentMatchers.any(ResourceSaveCommand.class)))
                .thenReturn(new StoredResource(
                        "resource-1",
                        "LOCAL_FILE",
                        "generated-files/2026/07/07/resource-1.pptx",
                        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                        "generated-resource-1.pptx",
                        9L,
                        null,
                        null
                ));

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

        ArgumentCaptor<ResourceSaveCommand> saveCommand = ArgumentCaptor.forClass(ResourceSaveCommand.class);
        verify(resourceStorage).save(saveCommand.capture());
        assertEquals("FILE", saveCommand.getValue().resourceType());
        assertEquals("application/vnd.openxmlformats-officedocument.presentationml.presentation", saveCommand.getValue().mimeType());
        assertEquals("pptx", saveCommand.getValue().extension());
        assertArrayEquals("ppt-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8), saveCommand.getValue().content());
        verify(chatSessionService).appendResourceMessage(1L, "session-1", "已发送文件：客户方案.pptx", "FILE", List.of(resourceDto));
    }

    @Test
    void shouldRejectSendingFilesOutsideSessionDirectory() {
        AssistantFileStorage assistantFileStorage = new AssistantFileStorage(tempDir.resolve("assistant-files"), 1024 * 1024);
        FileDeliveryTool tool = new FileDeliveryTool(
                assistantFileStorage,
                mock(ResourceStorage.class),
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
