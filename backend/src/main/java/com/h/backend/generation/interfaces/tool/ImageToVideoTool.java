package com.h.backend.generation.interfaces.tool;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatStreamEventBridge;
import com.h.backend.generation.application.command.SubmitImageToVideoCommand;
import com.h.backend.generation.application.port.in.SubmitImageToVideoUseCase;
import com.h.backend.generation.application.result.SubmitGenerationResult;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

@Component
public class ImageToVideoTool {
    private final SubmitImageToVideoUseCase submitImageToVideoUseCase;
    private final ChatSessionService chatSessionService;
    private final ChatStreamEventBridge chatStreamEventBridge;

    public ImageToVideoTool(
            SubmitImageToVideoUseCase submitImageToVideoUseCase,
            ChatSessionService chatSessionService,
            ChatStreamEventBridge chatStreamEventBridge
    ) {
        this.submitImageToVideoUseCase = submitImageToVideoUseCase;
        this.chatSessionService = chatSessionService;
        this.chatStreamEventBridge = chatStreamEventBridge;
    }

    @Tool(
            value = "基于用户提供的参考图片异步生成 MiniMax 视频。仅当用户希望让图片动起来、产生动画、主体或环境运动、镜头运动或视频时使用；静态图片修改请使用 generateImage。提交后立即返回，视频完成后自动出现在当前聊天中。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE
    )
    public String imageToVideo(
            @ToolMemoryId String memoryId,
            String referenceResourceId,
            String prompt,
            String originalPrompt,
            String model,
            Integer durationSeconds,
            String resolution,
            Boolean promptOptimizer,
            Boolean fastPretreatment,
            Boolean aigcWatermark
    ) {
        ChatContext context = ChatContext.parse(memoryId);
        SubmitGenerationResult result = submitImageToVideoUseCase.execute(new SubmitImageToVideoCommand(
                context.userId(), context.sessionId(), referenceResourceId,
                originalPrompt == null || originalPrompt.isBlank() ? prompt : originalPrompt,
                prompt, model, durationSeconds, resolution, promptOptimizer, fastPretreatment, aigcWatermark
        ));
        chatStreamEventBridge.publishMessage(
                memoryId,
                chatSessionService.getOwnedMessage(context.userId(), context.sessionId(), result.chatMessageId())
        );
        return "图生视频任务已提交（任务 ID：%s），完成后会自动显示在当前聊天中。".formatted(result.taskId());
    }

    private record ChatContext(Long userId, String sessionId) {
        private static ChatContext parse(String memoryId) {
            String[] parts = memoryId == null ? new String[0] : memoryId.split(":", 4);
            if (parts.length == 3) {
                return new ChatContext(Long.valueOf(parts[0]), parts[2]);
            }
            if (parts.length == 4 && "agent".equals(parts[1])) {
                return new ChatContext(Long.valueOf(parts[0]), parts[3]);
            }
            throw new IllegalArgumentException("Invalid chat memory id");
        }
    }
}
