package com.h.backend.order;

import com.h.backend.chat.domain.agent.*;
import com.h.backend.observability.agentscope.AgentScopeObservationInstaller;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.*;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import io.agentscope.harness.agent.tools.ToolsConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OrderHarnessConfig {
    public static final String INSTRUCTIONS = """
            你是当前用户独立的订单处理助手，服务内部订单运营。使用 MCP 工具的结果作为业务事实。
            先明确订单编号；不明确时 order_lookup 列举。仅查询时不得改变状态。
            用户要求处理后，委托 order-audit 审核，再委托 order-fulfillment 履约，异常/售后委托 order-aftersales。
            协作者必须先查询最新 revision，顺序执行有依赖的变更。不要反复派发相同委托。
            支付、签收、入库验收等外部事实必须等待外部系统更新，不得自行构造或模拟。
            取消、退货退款、改址和特批只提出业务方案，提示用户点击决策卡片；不得代用户确认。
            等待外部事实或人工决定时结束本轮，明确等待条件，不轮询、不声称订单已完成。
            发货、退款等结果必须以工具实际结果为准。状态冲突时重新查询，技术故障如实报告。
            对“以前如何处理过”等问题，通过 order_lookup 查询完整业务操作记录，不能只依赖记忆摘要。
            长期记忆只保存此订单助手内的稳定偏好和检索线索；不要把临时订单状态当作永恒事实。
            同一用户其他助手的会话、记忆和文件不可访问。子 Agent 只获得当前任务必要信息。
            汇总处理结果和下一步，用简洁中文；不要输出工具原始 JSON，业务卡片会展示结构化结果。
            """;

    @Bean(name = "orderHarnessAgent", destroyMethod = "close")
    public HarnessAgent orderHarness(@Qualifier("harnessModel") Model model,
                                     @Qualifier("harnessDistributedStore") DistributedStore distributedStore,
                                     @Qualifier("harnessMemoryConfig") MemoryConfig memory,
                                     @Qualifier("harnessCompactionConfig") CompactionConfig compaction,
                                     OrderAgentTools tools, AgentScopeObservationInstaller observation,
                                     ParentAssignmentSystemPromptMiddleware assignment, HarnessSubagentLifecycleMiddleware lifecycle,
                                     @Value("${chat.orders.workspace:/tmp/h-agent/order-workspace}") String workspace) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(tools);
        ToolsConfig config = new ToolsConfig();
        config.setDeny(List.of("agent_send", "agent_list", "session_list", "session_search", "session_history",
                "skill_manage", "propose_skill", "load_skill_through_path"));
        HarnessAgent agent = HarnessAgent.builder().agentId(ChatAgentIds.ORDER_PROCESSING).name(ChatAgentIds.ORDER_PROCESSING)
                .description("独立订单处理助手").sysPrompt(INSTRUCTIONS).model(model).toolkit(toolkit)
                .workspace(workspace).filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.USER))
                .distributedStore(distributedStore).memory(memory).compaction(compaction)
                .permissionContext(permissions())
                .middleware(observation.middleware()).disableShellTool().disableDynamicSubagents()
                .middleware(assignment).middleware(lifecycle)
                .middleware(new com.h.backend.chat.infrastructure.subagent.SubagentSpawnGuardMiddleware())
                .disableDynamicSkills().disableDefaultWorkspaceSkills().skillsEnabled(false).enableSkillManageTool(false)
                .toolsConfig(config).subagents(List.of(
                        declaration("order-audit", "订单审核：检查信息、支付和库存，使用 AUDIT；报告实际阻塞。"),
                        declaration("order-fulfillment", "订单履约：查询最新状态后使用 FULFILL；等待外部签收，不模拟事实。"),
                        declaration("order-aftersales", "售后异常：工单和取消/退货方案、失败退款重试；不可代用户确认。")))
                .subagentFactory("order-audit", session -> child("order-audit", "仅做订单审核，使用 AUDIT；不得履约或处理售后。", model, tools, distributedStore, observation, assignment, lifecycle, workspace))
                .subagentFactory("order-fulfillment", session -> child("order-fulfillment", "仅做履约，使用 FULFILL；不得提出退款或取消。", model, tools, distributedStore, observation, assignment, lifecycle, workspace))
                .subagentFactory("order-aftersales", session -> child("order-aftersales", "仅处理工单与售后，等待人工确认和外部事实。", model, tools, distributedStore, observation, assignment, lifecycle, workspace))
                .build();
        for (String name : config.getDeny()) agent.getToolkit().removeTool(name);
        agent.gateway();
        return agent;
    }

    private HarnessAgent child(String id, String purpose, Model model, OrderAgentTools tools, DistributedStore store,
                               AgentScopeObservationInstaller observation, ParentAssignmentSystemPromptMiddleware assignment,
                               HarnessSubagentLifecycleMiddleware lifecycle, String workspace) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new OrderWorkerTools(tools, id));
        HarnessAgent child = HarnessAgent.builder().agentId(ChatAgentIds.ORDER_PROCESSING + "-" + id).name(id)
                .sysPrompt(purpose + "\n" + INSTRUCTIONS + "\n你是叶子协作者，仅完成当前委托并返回工具证据。")
                .model(model).toolkit(toolkit).workspace(workspace + "/workers/" + id)
                .permissionContext(permissions())
                .distributedStore(store).filesystem(new RemoteFilesystemSpec().isolationScope(IsolationScope.SESSION))
                .disableSubagents().disableDynamicSkills().disableDefaultWorkspaceSkills().skillsEnabled(false)
                .disableMemoryTools().disableMemoryHooks().disableWorkspaceContext().disableFilesystemTools().disableShellTool()
                .middleware(observation.middleware()).middleware(assignment).middleware(lifecycle).build();
        for (String name : List.copyOf(child.getToolkit().getToolNames()))
            if (!List.of("order_lookup", "order_operate").contains(name)) child.getToolkit().removeTool(name);
        return child;
    }

    private io.agentscope.core.permission.PermissionContextState permissions() {
        var rules = io.agentscope.core.permission.PermissionContextState.builder();
        for (String name : List.of("order_lookup", "order_operate", "agent_spawn", "task", "task_output", "wait_async_results"))
            rules.addAllowRule(name, new io.agentscope.core.permission.PermissionRule(name, null,
                    io.agentscope.core.permission.PermissionBehavior.ALLOW, "order-business-policy"));
        return rules.build();
    }

    private SubagentDeclaration declaration(String name, String purpose) {
        return SubagentDeclaration.builder().name(name).description(purpose)
                .inlineAgentsBody(purpose + "\n" + INSTRUCTIONS + "\n你是受委托业务协作者，仅完成当前阶段并返回事实；不要继续委托。")
                .workspaceMode(WorkspaceMode.ISOLATED).mode(SubagentDeclaration.Mode.SUBAGENT)
                .persistSession(false).tools(List.of("order_lookup", "order_operate")).steps(8).build();
    }

    @Bean
    public AgentDefinition orderAgentDefinition(@Qualifier("orderHarnessAgent") HarnessAgent agent) {
        return new AgentDefinition(ChatAgentIds.ORDER_PROCESSING, "订单处理助手", "订单运营",
                List.of("订单", "履约", "售后"), "管理订单全流程，支持订单查询、工单跟进、履约与售后处理",
                agent, AgentRuntimeType.HARNESS_STREAMING, true);
    }
}
