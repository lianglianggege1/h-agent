package com.h.backend.chat.application;

import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

@Component
public class ChatStreamEventBridge {

    private final ConcurrentMap<String, Consumer<ChatSessionMessageDto>> messagePublishers = new ConcurrentHashMap<>();

    public void registerPublisher(String memoryId, Consumer<ChatSessionMessageDto> messagePublisher) {
        messagePublishers.put(memoryId, messagePublisher);
    }

    public void unregisterPublisher(String memoryId, Consumer<ChatSessionMessageDto> messagePublisher) {
        messagePublishers.remove(memoryId, messagePublisher);
    }

    public <T> T withPublisher(String memoryId, Consumer<ChatSessionMessageDto> messagePublisher, Supplier<T> action) {
        registerPublisher(memoryId, messagePublisher);
        try {
            return action.get();
        } finally {
            unregisterPublisher(memoryId, messagePublisher);
        }
    }

    public void publishMessage(String memoryId, ChatSessionMessageDto message) {
        Consumer<ChatSessionMessageDto> messagePublisher = messagePublishers.get(memoryId);
        if (messagePublisher == null) {
            return;
        }
        messagePublisher.accept(message);
    }

    public void publishImage(String memoryId, ChatSessionMessageDto message) {
        publishMessage(memoryId, message);
    }
}
