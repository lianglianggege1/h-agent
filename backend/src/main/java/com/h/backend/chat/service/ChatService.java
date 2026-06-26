package com.h.backend.chat.service;

import com.h.backend.chat.dto.ChatStreamEvent;
import com.h.backend.chat.dto.ChatMessageResourceUseDto;
import reactor.core.publisher.Flux;

import java.util.List;

public interface ChatService {

    Flux<ChatStreamEvent> streamChat(Long userId, Long promptId, String agentId, String sessionId, String userMessage, List<ChatMessageResourceUseDto> resources);
}
