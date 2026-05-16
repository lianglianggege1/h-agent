package com.h.backend.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;

import java.util.List;

/**
 * H会话内存
 */
public class HChatMemory implements ChatMemory {
    @Override
    public Object id() {
        return null;
    }

    @Override
    public void add(ChatMessage chatMessage) {

    }

    @Override
    public void add(ChatMessage... messages) {
        ChatMemory.super.add(messages);
    }

    @Override
    public void add(Iterable<ChatMessage> messages) {
        ChatMemory.super.add(messages);
    }

    @Override
    public void set(ChatMessage... messages) {
        ChatMemory.super.set(messages);
    }

    @Override
    public void set(Iterable<ChatMessage> messages) {
        ChatMemory.super.set(messages);
    }

    @Override
    public List<ChatMessage> messages() {
        return List.of();
    }

    @Override
    public void clear() {

    }
}
