package com.h.backend.chat.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.memory.ChatMemoryService;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;

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

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private Object id = ChatMemoryService.DEFAULT;
        private ChatMemoryStore store;
        private Boolean alwaysKeepSystemMessageFirst;

    }

}
