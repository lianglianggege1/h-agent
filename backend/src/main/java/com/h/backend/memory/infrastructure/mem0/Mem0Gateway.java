package com.h.backend.memory.infrastructure.mem0;

import com.h.backend.memory.domain.MemoryScopePolicy;

import java.util.List;

/**
 * 内部 port。searchExact 必须保证按 scope 的 AND 精确语义；
 * 远程 filter 不足时由实现方负责本地二次过滤。
 */
public interface Mem0Gateway {

    List<Mem0Models.Mem0Memory> searchExact(Mem0Models.Mem0SearchQuery query);

    /** 用户级宽搜索（管理页语义搜索）：仅按 mem0 user_id 过滤，不做 scope 过滤。 */
    List<Mem0Models.Mem0Memory> searchByUser(String mem0UserId, String query, int topK);

    /** Filters are constructed by the application module, never passed through from the model. */
    default List<Mem0Models.Mem0Memory> searchFiltered(String userId, String query, int topK,
                                                      java.util.Map<String,Object> filters) {
        return searchByUser(userId, query, topK);
    }

    default List<Mem0Models.Mem0Memory> findByOperationKey(String userId, String key, String text) {
        throw new UnsupportedOperationException("Exact operation lookup is unavailable");
    }

    Mem0Models.Mem0AddResult add(Mem0Models.Mem0AddCommand command);

    Mem0Models.Mem0Memory get(String remoteMemoryId, MemoryScopePolicy.MemoryOwnerScope scope);

    void update(String remoteMemoryId, String text, MemoryScopePolicy.MemoryOwnerScope scope);

    void delete(String remoteMemoryId, MemoryScopePolicy.MemoryOwnerScope scope);

    List<Mem0Models.Mem0HistoryEntry> history(String remoteMemoryId, MemoryScopePolicy.MemoryOwnerScope scope);
}
