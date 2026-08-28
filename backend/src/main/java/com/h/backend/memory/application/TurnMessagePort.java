package com.h.backend.memory.application;

/**
 * worker 回读已持久化 turn 正文的端口。outbox 不保存消息副本，
 * 只保存 agent_run 引用，由本端口从消息表加载正文。
 */
public interface TurnMessagePort {

    TurnTexts loadTurnTexts(Long agentRunId, Long userMessageId, Long assistantMessageId);

    record TurnTexts(String userMessage, String assistantMessage) {
    }
}
