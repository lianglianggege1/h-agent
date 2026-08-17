package com.h.backend.chat.domain.agent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

@Component
public class AgentScopeHarnessRuntime implements HarnessRuntime {

    @Override
    public Flux<AgentEvent> streamParent(Object agentBean, String message, RuntimeContext context) {
        return requireHarnessAgent(agentBean).streamEvents(message, context);
    }

    @Override
    public Flux<AgentEvent> streamSubagent(
            Object agentBean,
            HarnessSubagentContext context,
            String message
    ) {
        Msg userMessage = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(message)
                .build();
        ReActAgent child = materializeSubagent(agentBean, context);
        ensureAssignment(child, context);
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(context.userId())
                .sessionId(context.sessionId())
                .build();
        HarnessSubagentLifecycleMiddleware.stageExecutionId(runtimeContext, context.executionId());
        return child.streamEvents(List.of(userMessage), runtimeContext);
    }

    private ReActAgent materializeSubagent(Object agentBean, HarnessSubagentContext context) {
        HarnessAgent parent = requireHarnessAgent(agentBean);
        DefaultAgentManager manager = parent.getSubagentAgentManager();
        if (manager == null) {
            throw new IllegalStateException("Harness subagent manager is unavailable");
        }
        RuntimeContext parentContext = RuntimeContext.builder()
                .userId(context.userId())
                .sessionId(context.parentSessionId())
                .build();
        Agent child = manager.createAgentIfPresent(context.agentId(), parentContext)
                .orElseThrow(() -> new IllegalStateException(
                        "Unknown Harness subagent type: " + context.agentId()
                ));
        if (child instanceof HarnessAgent harnessChild) {
            return harnessChild.getDelegate();
        }
        if (child instanceof ReActAgent reactChild) {
            return reactChild;
        }
        throw new IllegalStateException(
                "Exposed Harness subagent must be ReActAgent or HarnessAgent: " + child.getClass().getName()
        );
    }

    private void ensureAssignment(ReActAgent child, HarnessSubagentContext context) {
        var state = child.getAgentState(context.userId(), context.sessionId());
        RuntimeContext runtimeContext = RuntimeContext.builder()
                .userId(context.userId())
                .sessionId(context.sessionId())
                .agentState(state)
                .build();
        if (ParentAssignmentSystemPromptMiddleware.upsertAssignment(
                runtimeContext, context.assignment()
        )) {
            child.saveAgentState(context.userId(), context.sessionId());
        }
    }

    private HarnessAgent requireHarnessAgent(Object agentBean) {
        if (agentBean instanceof HarnessAgent harnessAgent) {
            return harnessAgent;
        }
        throw new IllegalStateException("HARNESS_STREAMING agent bean must be HarnessAgent");
    }
}
