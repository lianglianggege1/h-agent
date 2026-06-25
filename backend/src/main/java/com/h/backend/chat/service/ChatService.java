package com.h.backend.chat.service;

import com.h.backend.chat.dto.ChatStreamEvent;
import reactor.core.publisher.Flux;

import java.util.List;

public interface ChatService {

    Flux<ChatStreamEvent> streamChat(Long userId, Long promptId, String agentId, String sessionId, String userMessage, List<String> referenceResourceIds);
}
