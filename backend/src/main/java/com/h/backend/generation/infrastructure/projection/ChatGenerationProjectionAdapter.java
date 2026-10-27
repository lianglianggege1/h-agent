package com.h.backend.generation.infrastructure.projection;

import com.h.backend.chat.application.ChatResourceUrls;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.generation.application.port.out.GenerationChatProjectionPort;
import com.h.backend.generation.domain.model.GenerationStatus;
import com.h.backend.generation.domain.model.GenerationTask;

import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class ChatGenerationProjectionAdapter implements GenerationChatProjectionPort {
    private final ChatSessionService chatSessionService;
    private final ChatResourceUrls chatResourceUrls;

    public ChatGenerationProjectionAdapter(
            ChatSessionService chatSessionService, ChatResourceUrls chatResourceUrls) {
        this.chatSessionService = chatSessionService;
        this.chatResourceUrls = chatResourceUrls;
    }

    @Override
    public Long createPendingMessage(GenerationTask task) {
        ChatSessionMessageDto message =
                chatSessionService.appendGeneratedMediaMessage(
                        task.userId(),
                        task.sessionId(),
                        task.spec() instanceof com.h.backend.generation.domain.model.VideoV2Spec v
                                        && v.taskType().equals("h3_context_ir")
                                ? "正在增强视频提示词，请继续聊天。"
                                : "正在生成视频，请继续聊天。");
        return Long.valueOf(message.id());
    }

    @Override
    public void updateMessage(GenerationTask task) {
        if (task.chatMessageId() == null) {
            return;
        }
        if (task.spec() instanceof com.h.backend.generation.domain.model.VideoV2Spec) {
            chatSessionService.updateGeneratedMediaMessage(
                    task.userId(),
                    task.sessionId(),
                    task.chatMessageId(),
                    content(task),
                    resources(task),
                    task.status().name());
        } else
            chatSessionService.updateGeneratedMediaMessage(
                    task.userId(),
                    task.sessionId(),
                    task.chatMessageId(),
                    content(task),
                    resources(task));
    }

    private String content(GenerationTask task) {
        if (task.resultText() != null && task.status() == GenerationStatus.SUCCEEDED)
            return task.resultText();
        boolean enhancement =
                task.spec() instanceof com.h.backend.generation.domain.model.VideoV2Spec spec
                        && spec.taskType().equals("h3_context_ir");
        return switch (task.status()) {
            case IN_PROGRESS -> enhancement ? "正在增强视频提示词，请继续聊天。" : "视频生成中，请继续聊天。";
            case MATERIALIZING -> "视频已生成，正在准备播放。";
            case RETRY_WAIT -> "任务暂时不可用，系统将自动恢复查询。";
            case SUCCEEDED -> "视频已生成。";
            case CANCELLED -> "视频任务已取消。";
            case SUBMISSION_UNKNOWN -> task.failureMessage();
            case FAILED -> "视频生成失败：" + task.failureMessage();
            case PENDING_SUBMISSION -> "正在提交视频任务。";
        };
    }

    private List<ChatMessageResourceDto> resources(GenerationTask task) {
        if (task.status() != GenerationStatus.SUCCEEDED || task.artifact() == null) {
            return List.of();
        }
        var artifact = task.artifact();
        return List.of(
                new ChatMessageResourceDto(
                        artifact.resourceId(),
                        "VIDEO",
                        "GENERATED",
                        chatResourceUrls.view(artifact.resourceId()),
                        chatResourceUrls.download(artifact.resourceId()),
                        artifact.fileName(),
                        artifact.mimeType(),
                        artifact.fileSize(),
                        null,
                        null,
                        artifact.storageType(),
                        artifact.storageKey()));
    }
}
