package com.h.backend.chat.domain.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import reactor.core.publisher.Flux;

/** 隔离应用层与 AgentScope Harness SDK 的父运行及 Gateway 子运行入口。 */
public interface HarnessRuntime {

    Flux<AgentEvent> streamParent(Object agentBean, String message, RuntimeContext context);

    /** 使用同一用户与子 Session 恢复上下文，然后追加本轮用户消息。 */
    Flux<AgentEvent> streamSubagent(Object agentBean, HarnessSubagentContext context, String message);

}
