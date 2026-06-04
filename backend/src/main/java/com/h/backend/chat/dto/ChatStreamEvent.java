package com.h.backend.chat.dto;

public record ChatStreamEvent(String type, String content, ChatSessionMessageDto message) {

    public ChatStreamEvent(String type, String content) {
        this(type, content, null);
    }
}
