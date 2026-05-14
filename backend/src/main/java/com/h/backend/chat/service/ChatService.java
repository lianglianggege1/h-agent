package com.h.backend.chat.service;

import java.util.function.Consumer;

public interface ChatService {

    String streamChat(Long userId, String userMessage, Consumer<String> onChunk);
}
