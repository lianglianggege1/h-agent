package com.h.backend.chat.domain.agent;

import com.h.backend.chat.domain.approval.ApprovalMode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import reactor.core.publisher.Flux;
import java.util.List;

/** 隔离应用层与 AgentScope Harness SDK 的父运行及 Gateway 子运行入口。 */
public interface HarnessRuntime {

    Flux<AgentEvent> streamParent(Object agentBean, String message, RuntimeContext context);

    default Flux<AgentEvent> streamParent(
            Object agentBean,
            String message,
            RuntimeContext context,
            ApprovalMode approvalMode
    ) {
        return streamParent(agentBean, message, context);
    }

    /** 使用同一用户与子 Session 恢复上下文，然后追加本轮用户消息。 */
    Flux<AgentEvent> streamSubagent(Object agentBean, HarnessSubagentContext context, String message);

    default Flux<AgentEvent> streamSubagent(
            Object agentBean,
            HarnessSubagentContext context,
            String message,
            ApprovalMode approvalMode
    ) {
        return streamSubagent(agentBean, context, message);
    }

    default Flux<AgentEvent> resumeParent(
            Object agentBean,
            RuntimeContext context,
            List<String> toolCallIds,
            boolean approved
    ) {
        return Flux.error(new UnsupportedOperationException("Harness approval resume is unavailable"));
    }

    default Flux<AgentEvent> resumeSubagent(
            Object agentBean,
            HarnessSubagentContext context,
            List<String> toolCallIds,
            boolean approved
    ) {
        return Flux.error(new UnsupportedOperationException("Harness approval resume is unavailable"));
    }

}
