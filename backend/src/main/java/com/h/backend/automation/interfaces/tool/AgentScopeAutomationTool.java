package com.h.backend.automation.interfaces.tool;

import com.h.backend.automation.application.AutomationProposalModule;
import com.h.backend.automation.application.AutomationTaskCommand;
import com.h.backend.automation.domain.AutomationProposal;
import com.h.backend.automation.domain.AutomationProposalAction;
import com.h.backend.automation.domain.AutomationDeliverySink;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class AgentScopeAutomationTool {

    private final AutomationProposalModule proposalModule;

    public AgentScopeAutomationTool(@Lazy AutomationProposalModule proposalModule) {
        this.proposalModule = proposalModule;
    }

    @Tool(
            name = "create_automation_task",
            description = "创建周期性自动化任务的提案。仅当用户明确要求定时、每天、每周或周期执行时调用。"
                    + "Cron 使用六段格式（秒 分 时 日 月 周），时区使用 IANA 名称。"
                    + "该工具只会生成提案；当前会话会显示确认卡片，用户点击“创建并开启”后生效。",
            concurrencySafe = false
    )
    public String create(
            RuntimeContext context,
            @ToolParam(name = "name", description = "简短任务名称") String name,
            @ToolParam(name = "instruction", description = "每次触发时交给协作 Agent 的完整任务内容") String instruction,
            @ToolParam(name = "cron_expression", description = "Spring 六段 Cron，例如每天 09:00 为 0 0 9 * * *") String cronExpression,
            @ToolParam(name = "zone_id", description = "IANA 时区，例如 Asia/Shanghai") String zoneId
    ) {
        if (context == null || context.getUserId() == null || context.getUserId().isBlank()) {
            throw new IllegalStateException("自动化工具缺少当前用户上下文");
        }
        AutomationProposal proposal = proposalModule.createChangeProposal(
                Long.valueOf(context.getUserId()),
                AutomationProposalAction.CREATE,
                null,
                new AutomationTaskCommand(
                        name, instruction, ChatAgentIds.HARNESS, null, cronExpression, zoneId, false,
                        AutomationDeliverySink.SESSION.name(), context.getSessionId()
                ),
                context.getSessionId(),
                "CHAT_AGENTSCOPE"
        );
        return "已生成自动化任务提案「%s」（提案编号：%s，24 小时内有效）。"
                .formatted(name, proposal.id())
                + "请提示用户直接使用当前会话中的确认卡片，确认前可继续讨论或取消。";
    }
}
