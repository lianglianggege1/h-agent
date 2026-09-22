package com.h.backend.chat.infrastructure.memory;

import com.h.backend.chat.application.ChatMemorySnapshotService;
import com.h.backend.chat.domain.memory.ChatMemoryContext;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RedisChatMemoryStore implements ChatMemoryStore {

    private final Map<String, DeferredMemory> deferred = new ConcurrentHashMap<>();

    private final ChatMemorySnapshotService chatMemorySnapshotService;
    private final ChatMemoryIdFactory chatMemoryIdFactory;

    public RedisChatMemoryStore(
            ChatMemorySnapshotService chatMemorySnapshotService
    ) {
        this(chatMemorySnapshotService, new ChatMemoryIdFactory());
    }

    @Autowired
    public RedisChatMemoryStore(ChatMemorySnapshotService chatMemorySnapshotService, ChatMemoryIdFactory chatMemoryIdFactory) {
        this.chatMemorySnapshotService = chatMemorySnapshotService;
        this.chatMemoryIdFactory = chatMemoryIdFactory;
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        var pending = deferred.get(String.valueOf(memoryId));
        if (pending != null) return pending.messages();
        ChatMemoryContext context = parseContext(memoryId);
        return chatMemorySnapshotService.loadSnapshot(context).orElse(List.of());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        var pending = deferred.get(String.valueOf(memoryId));
        if (pending != null) { pending.update(messages); return; }
        chatMemorySnapshotService.cacheMemory(parseContext(memoryId), messages);
    }

    @Override
    public void deleteMessages(Object memoryId) {
        chatMemorySnapshotService.deleteHotMemory(parseContext(memoryId));
    }

    /** The caller holds the session execution permit until the stream has actually stopped. */
    public DeferredMemory defer(String memoryId, List<ChatMessage> fallback) {
        return defer(memoryId, fallback, "");
    }

    public DeferredMemory defer(String memoryId, List<ChatMessage> fallback, String additionalInstructions) {
        var pending = new DeferredMemory(memoryId,
                chatMemorySnapshotService.loadSnapshot(parseContext(memoryId)).orElse(fallback), additionalInstructions);
        if (deferred.putIfAbsent(memoryId, pending) != null) {
            throw new IllegalStateException("Session already has a deferred generation");
        }
        return pending;
    }

    /** Apply temporary instructions to each model request, including tool follow-ups, without storing them. */
    public ChatRequest withGenerationInstructions(ChatRequest request, Object memoryId) {
        var pending = deferred.get(String.valueOf(memoryId));
        if (pending == null || pending.additionalInstructions.isBlank()) return request;
        var messages = new java.util.ArrayList<>(request.messages());
        for (int i = 0; i < messages.size(); i++) {
            if (messages.get(i) instanceof SystemMessage system) {
                messages.set(i, SystemMessage.from(system.text() + "\n\n" + pending.additionalInstructions));
                return request.toBuilder().messages(messages).build();
            }
        }
        messages.addFirst(SystemMessage.from(pending.additionalInstructions));
        return request.toBuilder().messages(messages).build();
    }

    public final class DeferredMemory implements AutoCloseable {
        private final String memoryId;
        private final List<ChatMessage> original;
        private final String additionalInstructions;
        private List<ChatMessage> messages;

        private DeferredMemory(String memoryId, List<ChatMessage> original, String additionalInstructions) {
            this.memoryId = memoryId;
            this.original = List.copyOf(original);
            this.additionalInstructions = additionalInstructions;
            this.messages = this.original;
        }

        public synchronized List<ChatMessage> messages() { return List.copyOf(messages); }
        private synchronized void update(List<ChatMessage> messages) { this.messages = List.copyOf(messages); }

        /** Keep completed tool facts; the final spoken answer is committed by playout settlement. */
        public synchronized List<ChatMessage> checkpoint() {
            var result = new java.util.ArrayList<>(messages);
            if (!messages.equals(original) && !result.isEmpty()
                    && result.getLast() instanceof dev.langchain4j.data.message.AiMessage ai
                    && !ai.hasToolExecutionRequests()) {
                result.removeLast();
            }
            // A provider/tool failure may leave an unmatched tool request. Never persist that tail.
            for (int i = 0; i < result.size(); i++) {
                if (!(result.get(i) instanceof dev.langchain4j.data.message.AiMessage ai)
                        || !ai.hasToolExecutionRequests()) continue;
                var expected = new java.util.HashSet<String>();
                ai.toolExecutionRequests().forEach(request -> expected.add(request.id()));
                int end = i + 1;
                while (end < result.size()
                        && result.get(end) instanceof dev.langchain4j.data.message.ToolExecutionResultMessage tool) {
                    expected.remove(tool.id());
                    end++;
                }
                if (!expected.isEmpty()) return List.copyOf(result.subList(0, i));
                i = end - 1;
            }
            return List.copyOf(result);
        }

        @Override public void close() { deferred.remove(memoryId, this); }
    }

    private ChatMemoryContext parseContext(Object memoryId) {
        return chatMemoryIdFactory.parse(memoryId);
    }
}
