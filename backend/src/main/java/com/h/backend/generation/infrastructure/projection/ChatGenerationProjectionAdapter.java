package com.h.backend.generation.infrastructure.projection;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.infrastructure.storage.ResourceStorage;
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
    private final ResourceStorage resourceStorage;

    public ChatGenerationProjectionAdapter(ChatSessionService chatSessionService, ResourceStorage resourceStorage) {
        this.chatSessionService = chatSessionService;
        this.resourceStorage = resourceStorage;
    }

    @Override
    public Long createPendingMessage(GenerationTask task) {
        ChatSessionMessageDto message = chatSessionService.appendGeneratedMediaMessage(
                task.userId(), task.sessionId(), "正在生成视频，请继续聊天。"
        );
        return Long.valueOf(message.id());
    }

    @Override
    public void updateMessage(GenerationTask task) {
        if (task.chatMessageId() == null) {
            return;
        }
        chatSessionService.updateGeneratedMediaMessage(
                task.userId(), task.sessionId(), task.chatMessageId(), content(task), resources(task)
        );
    }

    private String content(GenerationTask task) {
        return switch (task.status()) {
            case IN_PROGRESS -> "视频生成中，请继续聊天。";
            case MATERIALIZING -> "视频已生成，正在准备播放。";
            case RETRY_WAIT -> "视频任务暂时不可用，系统将自动重试。";
            case SUCCEEDED -> "视频已生成。";
            case FAILED -> "视频生成失败：" + task.failureMessage();
            case PENDING_SUBMISSION -> "正在提交视频任务。";
        };
    }

    private List<ChatMessageResourceDto> resources(GenerationTask task) {
        if (task.status() != GenerationStatus.SUCCEEDED || task.artifact() == null) {
            return List.of();
        }
        var artifact = task.artifact();
        return List.of(new ChatMessageResourceDto(
                artifact.resourceId(), "VIDEO", "GENERATED", resourceStorage.buildViewUrl(artifact.resourceId()),
                resourceStorage.buildDownloadUrl(artifact.resourceId()), artifact.fileName(), artifact.mimeType(),
                artifact.fileSize(), null, null, artifact.storageType(), artifact.storageKey()
        ));
    }
}
