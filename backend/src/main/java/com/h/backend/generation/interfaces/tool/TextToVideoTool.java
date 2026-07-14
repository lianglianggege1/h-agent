package com.h.backend.generation.interfaces.tool;

import com.h.backend.generation.application.command.SubmitTextToVideoCommand;
import com.h.backend.generation.application.port.in.SubmitTextToVideoUseCase;
import com.h.backend.generation.application.result.SubmitGenerationResult;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatStreamEventBridge;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

@Component
public class TextToVideoTool {
    private final SubmitTextToVideoUseCase submitTextToVideoUseCase;
    private final ChatSessionService chatSessionService;
    private final ChatStreamEventBridge chatStreamEventBridge;

    public TextToVideoTool(
            SubmitTextToVideoUseCase submitTextToVideoUseCase,
            ChatSessionService chatSessionService,
            ChatStreamEventBridge chatStreamEventBridge
    ) {
        this.submitTextToVideoUseCase = submitTextToVideoUseCase;
        this.chatSessionService = chatSessionService;
        this.chatStreamEventBridge = chatStreamEventBridge;
    }

    @Tool(
            value = "异步创建 MiniMax 文生视频任务。提交后立即返回，视频完成后自动出现在当前聊天中。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE
    )
    public String textToVideo(
            @ToolMemoryId String memoryId,
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
        SubmitGenerationResult result = submitTextToVideoUseCase.execute(new SubmitTextToVideoCommand(
                context.userId(), context.sessionId(),
                originalPrompt == null || originalPrompt.isBlank() ? prompt : originalPrompt,
                prompt, model, durationSeconds, resolution, promptOptimizer, fastPretreatment, aigcWatermark
        ));
        chatStreamEventBridge.publishMessage(
                memoryId,
                chatSessionService.getOwnedMessage(context.userId(), context.sessionId(), result.chatMessageId())
        );
        return "视频任务已提交（任务 ID：%s），完成后会自动显示在当前聊天中。".formatted(result.taskId());
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
