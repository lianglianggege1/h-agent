package com.h.backend.chat.domain.agent;

import com.h.agent.observability.lifecycle.ExecutionObservationCarrier;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import reactor.core.publisher.Flux;

/** 隔离应用层与 AgentScope Harness SDK 的父运行及 Gateway 子运行入口。 */
public interface HarnessRuntime {

    Flux<AgentEvent> streamParent(Object agentBean, String message, RuntimeContext context);

    /** 使用同一用户与子 Session 恢复上下文，然后追加本轮用户消息。 */
    Flux<AgentEvent> streamSubagent(Object agentBean, HarnessSubagentContext context, String message);

    /**
     * 同 {@link #streamSubagent(Object, HarnessSubagentContext, String)}，并把本轮执行的
     * 观测阶段载体作为类型化值放入子 RuntimeContext，使子 Agent 的观测 middleware 挂到
     * 该执行的 trace 下，且响应提交后的后置工作进入 Maintenance trace（设计 7.3 / 12.4）；
     * null 表示本轮无观测。
     */
    default Flux<AgentEvent> streamSubagent(
            Object agentBean, HarnessSubagentContext context, String message,
            ExecutionObservationCarrier carrier) {
        return streamSubagent(agentBean, context, message);
    }

}
