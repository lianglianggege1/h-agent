package com.h.backend.memory.application;

import com.h.backend.memory.domain.ExplicitMemoryDelete;
import com.h.backend.memory.domain.ExplicitMemorySave;
import com.h.backend.memory.domain.ExplicitMemoryUpdate;
import com.h.backend.memory.domain.MemoryHistory;
import com.h.backend.memory.domain.MemoryModuleDisabledException;
import com.h.backend.memory.domain.MemoryMutationResult;
import com.h.backend.memory.domain.MemoryPage;
import com.h.backend.memory.domain.MemoryView;
import com.h.backend.memory.domain.OwnedMemoryId;
import com.h.backend.memory.domain.OwnedMemoryQuery;
import com.h.backend.memory.domain.OwnedMemorySearch;

/** enabled=false 时装配；管理接口一律拒绝，不与远程交互。 */
public class DisabledUserMemoryCatalog implements UserMemoryCatalog {

    @Override
    public MemoryPage list(OwnedMemoryQuery query) {
        throw new MemoryModuleDisabledException();
    }

    @Override
    public MemoryPage search(OwnedMemorySearch query) {
        throw new MemoryModuleDisabledException();
    }

    @Override
    public MemoryView get(OwnedMemoryId id) {
        throw new MemoryModuleDisabledException();
    }

    @Override
    public MemoryMutationResult save(ExplicitMemorySave command) {
        throw new MemoryModuleDisabledException();
    }

    @Override
    public MemoryMutationResult update(ExplicitMemoryUpdate command) {
        throw new MemoryModuleDisabledException();
    }

    @Override
    public MemoryMutationResult delete(ExplicitMemoryDelete command) {
        throw new MemoryModuleDisabledException();
    }

    @Override
    public MemoryHistory history(OwnedMemoryId id) {
        throw new MemoryModuleDisabledException();
    }
}
