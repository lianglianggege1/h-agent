package com.h.backend.memory.infrastructure.mem0;

import com.h.backend.memory.domain.MemoryScopePolicy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/**
 * in-memory fake Adapter，供单元测试与本地无 Mem0 环境使用。
 * 保持与生产 Adapter 相同的 owner AND 精确语义：跨 owner 的 get/update/delete 一律不可见。
 */
public class InMemoryMem0Gateway implements Mem0Gateway {

    private static final double VECTOR_BASELINE_SCORE = 0.4;

    private record StoredMemory(
            String id,
            String text,
            MemoryScopePolicy.MemoryOwnerScope scope,
            Map<String, Object> metadata,
            Instant createdAt,
            Instant updatedAt
    ) {
    }

    private final List<StoredMemory> memories = new CopyOnWriteArrayList<>();
    private final Map<String, List<Mem0Models.Mem0HistoryEntry>> historyByMemoryId = new LinkedHashMap<>();
    private final AtomicLong idSequence = new AtomicLong();

    @Override
    public List<Mem0Models.Mem0Memory> searchExact(Mem0Models.Mem0SearchQuery query) {
        if (query == null || query.scope() == null) {
            return List.of();
        }
        return memories.stream()
                .filter(memory -> sameScope(memory.scope(), query.scope()))
                .map(memory -> new Mem0Models.Mem0Memory(
                        memory.id(),
                        memory.text(),
                        score(memory.text(), query.query(), VECTOR_BASELINE_SCORE),
                        memory.metadata(),
                        memory.createdAt(),
                        memory.updatedAt()
                ))
                .sorted(Comparator.comparingDouble(Mem0Models.Mem0Memory::score).reversed())
                .limit(query.topK() <= 0 ? Integer.MAX_VALUE : query.topK())
                .toList();
    }

    @Override
    public List<Mem0Models.Mem0Memory> searchByUser(String mem0UserId, String query, int topK) {
        if (mem0UserId == null || mem0UserId.isBlank()) {
            return List.of();
        }
        return memories.stream()
                .filter(memory -> memory.scope().mem0UserId().equals(mem0UserId))
                .map(memory -> new Mem0Models.Mem0Memory(
                        memory.id(),
                        memory.text(),
                        score(memory.text(), query),
                        memory.metadata(),
                        memory.createdAt(),
                        memory.updatedAt()
                ))
                .filter(memory -> memory.score() > 0)
                .sorted(Comparator.comparingDouble(Mem0Models.Mem0Memory::score).reversed())
                .limit(topK <= 0 ? Integer.MAX_VALUE : topK)
                .toList();
    }

    @Override
    public Mem0Models.Mem0AddResult add(Mem0Models.Mem0AddCommand command) {
        List<String> ids = new ArrayList<>();
        for (Mem0Models.Mem0Message message : command.messages()) {
            if (message.content() == null || message.content().isBlank()) {
                continue;
            }
            String id = "fake-mem-" + idSequence.incrementAndGet();
            Instant now = Instant.now();
            memories.add(new StoredMemory(id, message.content(), command.scope(), command.metadata(), now, now));
            recordHistory(id, message.content(), command.scope().scopeKind(), now);
            ids.add(id);
        }
        return new Mem0Models.Mem0AddResult(ids);
    }

    @Override
    public Mem0Models.Mem0Memory get(String remoteMemoryId, MemoryScopePolicy.MemoryOwnerScope scope) {
        return findOwned(remoteMemoryId, scope)
                .map(memory -> new Mem0Models.Mem0Memory(
                        memory.id(), memory.text(), null, memory.metadata(),
                        memory.createdAt(), memory.updatedAt()
                ))
                .orElse(null);
    }

    @Override
    public void update(String remoteMemoryId, String text, MemoryScopePolicy.MemoryOwnerScope scope) {
        StoredMemory memory = findOwned(remoteMemoryId, scope)
                .orElseThrow(() -> new IllegalArgumentException("memory not found: " + remoteMemoryId));
        Instant now = Instant.now();
        StoredMemory updated = new StoredMemory(
                memory.id(), text, memory.scope(), memory.metadata(), memory.createdAt(), now);
        memories.remove(memory);
        memories.add(updated);
        recordHistory(memory.id(), text, scope.scopeKind(), now);
    }

    @Override
    public void delete(String remoteMemoryId, MemoryScopePolicy.MemoryOwnerScope scope) {
        findOwned(remoteMemoryId, scope).ifPresent(memories::remove);
    }

    @Override
    public List<Mem0Models.Mem0HistoryEntry> history(String remoteMemoryId, MemoryScopePolicy.MemoryOwnerScope scope) {
        findOwned(remoteMemoryId, scope);
        return List.copyOf(historyByMemoryId.getOrDefault(remoteMemoryId, List.of()));
    }

    public int memoryCount() {
        return memories.size();
    }

    public List<String> ownedMemoryIds(MemoryScopePolicy.MemoryOwnerScope scope) {
        return memories.stream()
                .filter(memory -> sameScope(memory.scope(), scope))
                .map(StoredMemory::id)
                .toList();
    }

    public void clear() {
        memories.clear();
        historyByMemoryId.clear();
    }

    private Optional<StoredMemory> findOwned(String remoteMemoryId, MemoryScopePolicy.MemoryOwnerScope scope) {
        if (remoteMemoryId == null || scope == null) {
            return Optional.empty();
        }
        return memories.stream()
                .filter(memory -> memory.id().equals(remoteMemoryId) && sameScope(memory.scope(), scope))
                .findFirst();
    }

    private boolean sameScope(MemoryScopePolicy.MemoryOwnerScope left, MemoryScopePolicy.MemoryOwnerScope right) {
        return left.mem0UserId().equals(right.mem0UserId())
                && java.util.Objects.equals(left.mem0AgentId(), right.mem0AgentId())
                && java.util.Objects.equals(left.mem0RunId(), right.mem0RunId())
                && left.scopeKind() == right.scopeKind();
    }

    private void recordHistory(String id, String text, com.h.backend.memory.domain.MemoryScopeKind scopeKind, Instant at) {
        historyByMemoryId.computeIfAbsent(id, key -> new CopyOnWriteArrayList<>())
                .add(new Mem0Models.Mem0HistoryEntry(id, text, scopeKind, at));
    }

    private double score(String text, String query) {
        return score(text, query, 0);
    }

    /** baseline>0 模拟向量检索：scope 内近邻总是带分返回，而非按字面匹配过滤。 */
    private double score(String text, String query, double baseline) {
        if (query == null || query.isBlank()) {
            return Math.max(0.5, baseline);
        }
        String lowerText = text.toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        if (lowerText.contains(lowerQuery)) {
            return 1.0;
        }
        for (String token : lowerQuery.split("\\s+")) {
            if (!token.isBlank() && lowerText.contains(token)) {
                return 0.6;
            }
        }
        return baseline;
    }
}
