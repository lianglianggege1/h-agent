package com.h.otheragents.team.tool.springai;

import com.h.backend.memory.application.AgentMemoryContext;
import org.springframework.ai.chat.model.ToolContext;

/**
 * SpringWorker 每轮执行固定的可信身份，经 ChatClient.toolContext 注入，
 * 由原生 @Tool 方法的 ToolContext 参数取出（不暴露给模型）。
 */
public record SpringToolContext(long userId, String roomId, String memoryId, AgentMemoryContext memory) {

    public static final String KEY = "h-agent.team.tool-context";

    public static SpringToolContext from(ToolContext toolContext) {
        return (SpringToolContext) toolContext.getContext().get(KEY);
    }
}