package com.h.backend.chat.infrastructure.capability;

import com.h.backend.capability.port.LiveProjectionPort;
import com.h.backend.chat.application.ChatStreamEventBridge;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import org.springframework.stereotype.Component;

/** 产品聊天实时投影：把交付挂接返回的消息 DTO 发布到 SSE 流。 */
@Component
public class ChatLiveProjectionAdapter implements LiveProjectionPort {

    private final ChatStreamEventBridge bridge;

    public ChatLiveProjectionAdapter(ChatStreamEventBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public void publish(String memoryId, Object projectionHandle) {
        if (projectionHandle instanceof ChatSessionMessageDto message) {
            bridge.publishMessage(memoryId, message);
        }
    }
}