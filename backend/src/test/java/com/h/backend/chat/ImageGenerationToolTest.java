package com.h.backend.chat;

import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.service.ChatStreamEventBridge;
import com.h.backend.chat.service.ImageGenerationService;
import com.h.backend.chat.tools.ImageGenerationTool;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ImageGenerationToolTest {

    @Test
    void shouldGenerateImageAndPublishImageEventForCurrentStream() {
        ImageGenerationService imageGenerationService = mock(ImageGenerationService.class);
        ChatStreamEventBridge bridge = new ChatStreamEventBridge();
        ImageGenerationTool tool = new ImageGenerationTool(imageGenerationService, bridge);
        ChatSessionMessageDto imageMessage = new ChatSessionMessageDto(
                "501",
                "assistant",
                "IMAGE",
                "一只白猫",
                null,
                List.of(),
                LocalDateTime.now()
        );

        when(imageGenerationService.generateImage(new ImageGenerationService.ImageGenerationCommand(
                1L,
                "session-1",
                22L,
                "一只白猫",
                "TOOL"
        ))).thenReturn(imageMessage);

        java.util.ArrayList<ChatSessionMessageDto> published = new java.util.ArrayList<>();
        String result = bridge.withPublisher(
                "1:22:session-1",
                published::add,
                () -> tool.generateImage("1:22:session-1", "一只白猫")
        );

        assertEquals("图片已生成并发送到聊天中。", result);
        assertEquals(List.of(imageMessage), published);
        verify(imageGenerationService).generateImage(new ImageGenerationService.ImageGenerationCommand(
                1L,
                "session-1",
                22L,
                "一只白猫",
                "TOOL"
        ));
    }
}
