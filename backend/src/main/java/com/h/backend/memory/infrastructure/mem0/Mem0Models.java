package com.h.backend.memory.infrastructure.mem0;

import com.h.backend.memory.domain.MemoryScopePolicy;
import com.h.backend.memory.domain.MemoryScopeKind;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Mem0 网关的请求/响应模型。Mem0 URL、JSON 形状只允许在生产 Adapter 中出现。 */
public final class Mem0Models {

    private Mem0Models() {
    }

    public record Mem0Message(String role, String content) {
        public static Mem0Message user(String content) {
            return new Mem0Message("user", content);
        }

        public static Mem0Message assistant(String content) {
            return new Mem0Message("assistant", content);
        }
    }

    public record Mem0SearchQuery(
            MemoryScopePolicy.MemoryOwnerScope scope,
            String query,
            int topK
    ) {
    }

    public record Mem0AddCommand(
            MemoryScopePolicy.MemoryOwnerScope scope,
            List<Mem0Message> messages,
            boolean infer,
            Map<String, Object> metadata
    ) {
        public Mem0AddCommand {
            messages = List.copyOf(messages);
        }
    }

    public record Mem0Memory(
            String id,
            String text,
            Double score,
            Map<String, Object> metadata,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    public record Mem0AddResult(List<String> memoryIds) {
        public Mem0AddResult {
            memoryIds = memoryIds == null ? List.of() : List.copyOf(memoryIds);
        }
    }

    public record Mem0HistoryEntry(
            String id,
            String text,
            MemoryScopeKind scopeKind,
            Instant createdAt
    ) {
    }
}
