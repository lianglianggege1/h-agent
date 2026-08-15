package com.h.backend.chat.application;

import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceUseDto;
import reactor.core.publisher.Flux;

import java.util.List;

public interface ChatService {

    Flux<ChatStreamEvent> streamChat(
            Long userId,
            Long promptId,
            String agentId,
            String sessionId,
            String userMessage,
            List<ChatMessageResourceUseDto> resources
    );
}
