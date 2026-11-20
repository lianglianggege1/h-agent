package com.h.backend.chat.infrastructure.capability;

import com.h.capabilities.seam.DeliveredArtifact;
import com.h.capabilities.seam.DeliveryResult;
import com.h.capabilities.seam.port.ArtifactDeliveryPort;
import com.h.backend.chat.application.ChatResourceUrls;
import com.h.backend.chat.application.ChatSessionService;
import com.h.capabilities.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.capabilities.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.capabilities.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.capabilities.chat.interfaces.dto.ChatSessionMessageDto;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.function.Consumer;

/** 产品聊天交付：对象保存与文件消息挂接同事务，返回 SSE 投影凭据。 */
@Component
public class ChatArtifactDeliveryAdapter implements ArtifactDeliveryPort {

    private final ResourceWriteCoordinator writes;
    private final ChatSessionService sessions;
    private final ChatResourceUrls urls;

    public ChatArtifactDeliveryAdapter(
            ResourceWriteCoordinator writes, ChatSessionService sessions, ChatResourceUrls urls) {
        this.writes = writes;
        this.sessions = sessions;
        this.urls = urls;
    }

    @Override
    public DeliveryResult deliver(
            long userId,
            String scopeId,
            ResourceSaveCommand command,
            String resourceType,
            String fileName,
            String mimeType,
            String text,
            Consumer<DeliveredArtifact> onAttached) {
        DeliveredArtifact[] artifact = new DeliveredArtifact[1];
        ChatSessionMessageDto message = writes.saveAndAttach(command, stored -> {
            artifact[0] = new DeliveredArtifact(
                    stored.id(),
                    resourceType,
                    urls.view(stored.id()),
                    urls.download(stored.id()),
                    fileName,
                    mimeType,
                    stored.fileSize(),
                    stored.width(),
                    stored.height(),
                    stored.storageType(),
                    stored.storageKey());
            onAttached.accept(artifact[0]);
            ChatMessageResourceDto resource = new ChatMessageResourceDto(
                    stored.id(),
                    resourceType,
                    "GENERATED",
                    artifact[0].viewUrl(),
                    artifact[0].downloadUrl(),
                    fileName,
                    mimeType,
                    stored.fileSize(),
                    stored.width(),
                    stored.height(),
                    stored.storageType(),
                    stored.storageKey());
            return sessions.appendResourceMessage(userId, scopeId, text, resourceType, List.of(resource));
        });
        return new DeliveryResult(artifact[0], message);
    }
}