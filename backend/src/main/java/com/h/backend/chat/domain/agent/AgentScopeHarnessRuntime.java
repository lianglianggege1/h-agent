package com.h.backend.chat.domain.agent;

import com.h.agent.observability.lifecycle.ExecutionObservationCarrier;
import com.h.backend.chat.domain.subagentdefinition.SubagentDefinitionCatalog;
import com.h.backend.chat.domain.subagentdefinition.SubagentRuntimeFactory;
import com.h.backend.chat.domain.subagentdefinition.model.ResolvedSubagentDefinition;
import com.h.backend.chat.domain.approval.ApprovalMode;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.subagent.DefaultAgentManager;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * Harness 执行的 AgentScope 实现。
 *
 * <p>Catalog 子会话（带 pinned {@code DefinitionBinding}）按固定版本重新物化：
 * 停用、软删除或发布新版本都不改变子会话已绑定的版本；当前安全政策重新求
 * 能力交集（设计 7.6）。无绑定的会话保持 SDK 静态 factory 路径。</p>
 */
@Component
public class AgentScopeHarnessRuntime implements HarnessRuntime {

    private final ObjectProvider<SubagentDefinitionCatalog> subagentCatalogProvider;
    private final ObjectProvider<SubagentRuntimeFactory> subagentRuntimeFactoryProvider;
    private final AgentScopeApprovalAdapter approvalAdapter;

    @Autowired
    public AgentScopeHarnessRuntime(
            ObjectProvider<SubagentDefinitionCatalog> subagentCatalogProvider,
            ObjectProvider<SubagentRuntimeFactory> subagentRuntimeFactoryProvider,
            AgentScopeApprovalAdapter approvalAdapter
    ) {
        this.subagentCatalogProvider = subagentCatalogProvider;
        this.subagentRuntimeFactoryProvider = subagentRuntimeFactoryProvider;
        this.approvalAdapter = approvalAdapter;
    }

    public AgentScopeHarnessRuntime(
            ObjectProvider<SubagentDefinitionCatalog> subagentCatalogProvider,
            ObjectProvider<SubagentRuntimeFactory> subagentRuntimeFactoryProvider
    ) {
        this(subagentCatalogProvider, subagentRuntimeFactoryProvider,
                new AgentScopeApprovalAdapter("/tmp/h-agent/harness-workspace"));
    }

    @Override
    public Flux<AgentEvent> streamParent(Object agentBean, String message, RuntimeContext context) {
        return streamParent(agentBean, message, context, null);
    }

    @Override
    public Flux<AgentEvent> streamParent(
            Object agentBean,
            String message,
            RuntimeContext context,
            ApprovalMode approvalMode
    ) {
        HarnessAgent harnessAgent = requireHarnessAgent(agentBean);
        if (approvalMode != null) {
            approvalAdapter.applyMode(
                    harnessAgent.getDelegate(),
                    context.getUserId(),
                    context.getSessionId(),
                    approvalMode
            );
        }
        return harnessAgent.streamEvents(message, context);
    }

    @Override
    public Flux<AgentEvent> streamSubagent(
            Object agentBean,
            HarnessSubagentContext context,
            String message
    ) {
        return streamSubagent(agentBean, context, message, null, null);
    }

    @Override
    public Flux<AgentEvent> streamSubagent(
            Object agentBean,
            HarnessSubagentContext context,
            String message,
            ExecutionObservationCarrier carrier,
            ApprovalMode approvalMode
    ) {
        Msg userMessage = Msg.builder()
                .name("user")
                .role(MsgRole.USER)
                .textContent(message)
                .build();
        ReActAgent child = materializeSubagent(agentBean, context);
        ensureAssignment(child, context);
        if (approvalMode != null) {
            approvalAdapter.applyMode(
                    child,
                    context.userId(),
                    context.sessionId(),
                    approvalMode
            );
        }
        RuntimeContext.Builder contextBuilder = RuntimeContext.builder()
                .userId(context.userId())
                .sessionId(context.sessionId());
        if (carrier != null) {
            // 类型化阶段载体（设计 7.3 / 12.4）：子 Agent 的观测 middleware 据此挂到本轮
            // 执行 trace，且响应提交后的后置工作进入 Maintenance trace。
            contextBuilder.put(ExecutionObservationCarrier.class, carrier);
        }
        RuntimeContext runtimeContext = contextBuilder.build();
        HarnessSubagentLifecycleMiddleware.stageExecutionId(runtimeContext, context.executionId());
        return child.streamEvents(List.of(userMessage), runtimeContext);
    }

    @Override
    public Flux<AgentEvent> resumeParent(
            Object agentBean,
            RuntimeContext context,
            List<String> toolCallIds,
            boolean approved
    ) {
        HarnessAgent parent = requireHarnessAgent(agentBean);
        Msg confirmation = approvalAdapter.confirmationMessage(
                parent.getDelegate(), context.getUserId(), context.getSessionId(),
                toolCallIds, approved
        );
        return parent.streamEvents(List.of(confirmation), context);
    }

    @Override
    public Flux<AgentEvent> resumeSubagent(
            Object agentBean,
            HarnessSubagentContext context,
            List<String> toolCallIds,
            boolean approved,
            ExecutionObservationCarrier carrier
    ) {
        ReActAgent child = materializeSubagent(agentBean, context);
        ensureAssignment(child, context);
        Msg confirmation = approvalAdapter.confirmationMessage(
                child, context.userId(), context.sessionId(), toolCallIds, approved
        );
        RuntimeContext.Builder contextBuilder = RuntimeContext.builder()
                .userId(context.userId())
                .sessionId(context.sessionId());
        if (carrier != null) {
            contextBuilder.put(ExecutionObservationCarrier.class, carrier);
        }
        RuntimeContext runtimeContext = contextBuilder.build();
        HarnessSubagentLifecycleMiddleware.stageExecutionId(runtimeContext, context.executionId());
        return child.streamEvents(List.of(confirmation), runtimeContext);
    }

    private ReActAgent materializeSubagent(Object agentBean, HarnessSubagentContext context) {
        HarnessAgent parent = requireHarnessAgent(agentBean);
        RuntimeContext parentContext = RuntimeContext.builder()
                .userId(context.userId())
                .sessionId(context.parentSessionId())
                .build();
        if (context.definitionBinding() != null) {
            return materializePinned(context, parentContext);
        }
        return materializeViaStaticFactory(parent, context, parentContext);
    }

    /**
     * Catalog 子会话固定版本物化（设计 7.6）：agent_sessions 的 pinned binding
     * 经 {@code resolvePinned} 解析到确切版本，再交给平台 runtime factory。
     */
    private ReActAgent materializePinned(HarnessSubagentContext context, RuntimeContext parentContext) {
        SubagentDefinitionCatalog catalog = subagentCatalogProvider == null
                ? null : subagentCatalogProvider.getIfAvailable();
        SubagentRuntimeFactory runtimeFactory = subagentRuntimeFactoryProvider == null
                ? null : subagentRuntimeFactoryProvider.getIfAvailable();
        if (catalog == null || runtimeFactory == null) {
            throw new IllegalStateException(
                    "Pinned subagent session requires the Subagent Definition Catalog: " + context.agentId());
        }
        ResolvedSubagentDefinition definition = catalog.resolvePinned(
                Long.parseLong(context.userId()), context.definitionBinding());
        return runtimeFactory.materialize(definition, parentContext);
    }

    private ReActAgent materializeViaStaticFactory(
            HarnessAgent parent, HarnessSubagentContext context, RuntimeContext parentContext) {
        DefaultAgentManager manager = parent.getSubagentAgentManager();
        if (manager == null) {
            throw new IllegalStateException("Harness subagent manager is unavailable");
        }
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
