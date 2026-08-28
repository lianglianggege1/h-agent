package com.h.backend.memory.application;

import com.h.backend.memory.domain.CompletedTurn;
import com.h.backend.memory.domain.MemoryRecallCommand;
import com.h.backend.memory.domain.MemoryRecallResult;

/**
 * Agent 执行路径唯一需要学习的长期记忆接口。recall 隐藏分层搜索、并发、去重、
 * 预算与 fail-open；stageCapture 只写本地 outbox 并参与调用方事务，不调用 Mem0。
 */
public interface LongTermMemoryRuntime {

    MemoryRecallResult recall(MemoryRecallCommand command);

    void stageCapture(CompletedTurn turn);
}
