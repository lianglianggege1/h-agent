package com.h.backend.memory.application;

import com.h.backend.memory.domain.ExplicitMemoryDelete;
import com.h.backend.memory.domain.ExplicitMemorySave;
import com.h.backend.memory.domain.ExplicitMemoryUpdate;
import com.h.backend.memory.domain.MemoryHistory;
import com.h.backend.memory.domain.MemoryMutationResult;
import com.h.backend.memory.domain.MemoryPage;
import com.h.backend.memory.domain.MemoryView;
import com.h.backend.memory.domain.OwnedMemoryId;
import com.h.backend.memory.domain.OwnedMemoryQuery;
import com.h.backend.memory.domain.OwnedMemorySearch;

/**
 * 用户记忆目录：管理页、REST Controller 与显式 Memory Tools 共用。
 * 隐藏 owner 校验、本地 version CAS、409、Mem0 操作、结果不明 reconciliation 与分页实现。
 */
public interface UserMemoryCatalog {

    MemoryPage list(OwnedMemoryQuery query);

    MemoryPage search(OwnedMemorySearch query);

    MemoryView get(OwnedMemoryId id);

    MemoryMutationResult save(ExplicitMemorySave command);

    MemoryMutationResult update(ExplicitMemoryUpdate command);

    MemoryMutationResult delete(ExplicitMemoryDelete command);

    MemoryHistory history(OwnedMemoryId id);
}
