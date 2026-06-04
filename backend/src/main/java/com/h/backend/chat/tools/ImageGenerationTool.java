package com.h.backend.chat.tools;

import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.service.ChatStreamEventBridge;
import com.h.backend.chat.service.ImageGenerationService;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

@Component
public class ImageGenerationTool {

    private final ImageGenerationService imageGenerationService;
    private final ChatStreamEventBridge chatStreamEventBridge;

    public ImageGenerationTool(ImageGenerationService imageGenerationService, ChatStreamEventBridge chatStreamEventBridge) {
        this.imageGenerationService = imageGenerationService;
        this.chatStreamEventBridge = chatStreamEventBridge;
    }

    @Tool(value = "根据用户提示生成一张图片，并把图片发送到当前聊天中。", searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String generateImage(@ToolMemoryId String memoryId, String prompt) {
        ImageGenerationContext context = parseMemoryId(memoryId);
        ChatSessionMessageDto message = imageGenerationService.generateImage(
                new ImageGenerationService.ImageGenerationCommand(
                        context.userId(),
                        context.sessionId(),
                        context.promptId(),
                        prompt,
                        "TOOL"
                )
        );
        chatStreamEventBridge.publishImage(memoryId, message);
        return "图片已生成并发送到聊天中。";
    }

    private ImageGenerationContext parseMemoryId(String memoryId) {
        String[] parts = memoryId == null ? new String[0] : memoryId.split(":", 3);
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid chat memory id");
        }
        return new ImageGenerationContext(Long.valueOf(parts[0]), Long.valueOf(parts[1]), parts[2]);
    }

    private record ImageGenerationContext(Long userId, Long promptId, String sessionId) {
    }
}
