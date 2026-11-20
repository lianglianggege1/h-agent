package com.h.capabilities.memory.application;

import com.h.capabilities.memory.domain.CompletedTurn;
import com.h.capabilities.memory.domain.MemoryRecallCommand;
import com.h.capabilities.memory.domain.MemoryRecallResult;

/** enabled=false 时装配；Agent 正常聊天但不 recall/capture。 */
public class NoopLongTermMemoryRuntime implements LongTermMemoryRuntime {

    @Override
    public MemoryRecallResult recall(MemoryRecallCommand command) {
        return MemoryRecallResult.empty();
    }

    @Override
    public void stageCapture(CompletedTurn turn) {
    }
}
