package com.h.backend.chat.service;

import com.h.backend.chat.dto.ChatSessionMessageDto;
import org.springframework.stereotype.Component;

import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
public class ChatStreamEventBridge {

    private final ThreadLocal<StreamPublisher> currentPublisher = new ThreadLocal<>();

    public <T> T withPublisher(String memoryId, Consumer<ChatSessionMessageDto> imagePublisher, Supplier<T> action) {
        currentPublisher.set(new StreamPublisher(memoryId, imagePublisher));
        try {
            return action.get();
        } finally {
            currentPublisher.remove();
        }
    }

    public void publishImage(String memoryId, ChatSessionMessageDto message) {
        StreamPublisher publisher = currentPublisher.get();
        if (publisher == null || !publisher.memoryId().equals(memoryId)) {
            return;
        }
        publisher.imagePublisher().accept(message);
    }

    private record StreamPublisher(String memoryId, Consumer<ChatSessionMessageDto> imagePublisher) {
    }
}
