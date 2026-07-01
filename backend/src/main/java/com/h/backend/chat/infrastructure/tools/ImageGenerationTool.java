package com.h.backend.chat.infrastructure.tools;

import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.application.ChatStreamEventBridge;
import com.h.backend.chat.application.ImageSubAgentService;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

@Component
public class ImageGenerationTool {

    private final ImageSubAgentService imageSubAgentService;
    private final ChatStreamEventBridge chatStreamEventBridge;

    public ImageGenerationTool(ImageSubAgentService imageSubAgentService, ChatStreamEventBridge chatStreamEventBridge) {
        this.imageSubAgentService = imageSubAgentService;
        this.chatStreamEventBridge = chatStreamEventBridge;
    }

    @Tool(value = "根据用户提示生成一张图片，可选择传入参考图片，并把图片发送到当前聊天中。", searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String generateImage(@ToolMemoryId String memoryId, String prompt, String referenceResourceId) {
        ImageGenerationContext context = parseMemoryId(memoryId);
        ChatSessionMessageDto message = imageSubAgentService.generateImage(
                new ImageSubAgentService.ImageSubAgentCommand(
                        context.userId(),
                        context.sessionId(),
                        context.promptId(),
                        prompt,
                        "TOOL",
                        referenceResourceId,
                        null,
                        "GENERATE"
                )
        );
        chatStreamEventBridge.publishImage(memoryId, message);
        return "图片已生成并发送到聊天中。";
    }

    private ImageGenerationContext parseMemoryId(String memoryId) {
        String[] parts = memoryId == null ? new String[0] : memoryId.split(":", 4);
        if (parts.length == 3) {
            return new ImageGenerationContext(Long.valueOf(parts[0]), Long.valueOf(parts[1]), parts[2]);
        }
        if (parts.length == 4 && "agent".equals(parts[1])) {
            return new ImageGenerationContext(Long.valueOf(parts[0]), null, parts[3]);
        }
        throw new IllegalArgumentException("Invalid chat memory id");
    }

    private record ImageGenerationContext(Long userId, Long promptId, String sessionId) {
    }
}
