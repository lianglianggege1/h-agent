package com.h.backend.chat.service;

import com.h.backend.chat.dto.ChatStreamEvent;
import reactor.core.publisher.Flux;

public interface ChatService {

    Flux<ChatStreamEvent> streamChat(Long userId, Long promptId, String agentId, String sessionId, String userMessage);
}
