package com.h.backend.chat.service;

import com.h.backend.chat.dto.ChatSessionMessageDto;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
public class ChatStreamEventBridge {

    private final ConcurrentMap<String, Consumer<ChatSessionMessageDto>> imagePublishers = new ConcurrentHashMap<>();

    public void registerPublisher(String memoryId, Consumer<ChatSessionMessageDto> imagePublisher) {
        imagePublishers.put(memoryId, imagePublisher);
    }

    public void unregisterPublisher(String memoryId, Consumer<ChatSessionMessageDto> imagePublisher) {
        imagePublishers.remove(memoryId, imagePublisher);
    }

    public <T> T withPublisher(String memoryId, Consumer<ChatSessionMessageDto> imagePublisher, Supplier<T> action) {
        registerPublisher(memoryId, imagePublisher);
        try {
            return action.get();
        } finally {
            unregisterPublisher(memoryId, imagePublisher);
        }
    }

    public void publishImage(String memoryId, ChatSessionMessageDto message) {
        Consumer<ChatSessionMessageDto> imagePublisher = imagePublishers.get(memoryId);
        if (imagePublisher == null) {
            return;
        }
        imagePublisher.accept(message);
    }
}
