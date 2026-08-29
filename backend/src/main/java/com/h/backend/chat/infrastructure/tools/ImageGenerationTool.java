package com.h.backend.chat.infrastructure.tools;

import com.h.agent.observability.semantic.ArtifactReference;
import com.h.agent.observability.semantic.ArtifactUse;
import com.h.agent.observability.semantic.ToolArtifactCollector;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.application.ChatStreamEventBridge;
import com.h.backend.chat.application.ImageSubAgentService;
import com.h.backend.observability.BusinessArtifactReferenceMapper;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ImageGenerationTool {

    private final ImageSubAgentService imageSubAgentService;
    private final ChatStreamEventBridge chatStreamEventBridge;

    public ImageGenerationTool(ImageSubAgentService imageSubAgentService, ChatStreamEventBridge chatStreamEventBridge) {
        this.imageSubAgentService = imageSubAgentService;
        this.chatStreamEventBridge = chatStreamEventBridge;
    }

    @Tool(value = "生成或修改静态图片，可选择传入参考图片并把图片发送到当前聊天中。用于重绘、换风格、换背景等静态编辑；不用于让图片动起来或生成视频。", searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
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
        // 同步生成图片已随 generateImage 提交（设计 §9.9）：只做已持有业务结果的纯映射。
        List<ChatMessageResourceDto> resources =
                message == null || message.resources() == null ? List.of() : message.resources();
        for (ChatMessageResourceDto resource : resources) {
            ArtifactReference reference = BusinessArtifactReferenceMapper.from(
                    resource, ArtifactUse.TOOL_OUTPUT, null);
            ToolArtifactCollector.record(reference);
        }
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
