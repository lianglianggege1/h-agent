package com.h.backend.chat.dto;

public record ChatStreamEvent(String type, String content, ChatSessionMessageDto message, Object payload) {

    public ChatStreamEvent(String type, String content) {
        this(type, content, null, null);
    }

    public ChatStreamEvent(String type, String content, ChatSessionMessageDto message) {
        this(type, content, message, null);
    }
}
