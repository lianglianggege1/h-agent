package com.h.backend.automation.interfaces.tool;

import com.h.backend.automation.application.AutomationProposalModule;
import com.h.backend.automation.application.AutomationProposalActions;
import com.h.backend.automation.application.AutomationTaskCommand;
import com.h.backend.automation.domain.AutomationDeliverySink;
import com.h.backend.automation.domain.AutomationProposal;
import com.h.backend.automation.domain.AutomationProposalAction;
import com.h.capabilities.chat.domain.agent.ChatAgentIds;
import com.h.capabilities.chat.domain.memory.ChatMemoryContext;
import com.h.capabilities.chat.domain.memory.ChatMemoryIdFactory;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class LangChain4jAutomationTool {

    private final AutomationProposalModule proposalModule;
    private final AutomationProposalActions proposalActions;
    private final ChatMemoryIdFactory memoryIdFactory;

    public LangChain4jAutomationTool(
            @Lazy AutomationProposalModule proposalModule,
            @Lazy AutomationProposalActions proposalActions,
            ChatMemoryIdFactory memoryIdFactory
    ) {
        this.proposalModule = proposalModule;
        this.proposalActions = proposalActions;
        this.memoryIdFactory = memoryIdFactory;
    }

    @Tool(
            name = "create_automation_task",
            value = "创建周期性自动化任务的提案。仅当用户明确要求定时、每天、每周或周期执行时调用。"
                    + "使用 Spring 六段 Cron（秒 分 时 日 月 周）并明确 IANA 时区。"
                    + "该工具只会生成提案；当前会话会显示确认卡片，用户点击“创建并开启”后生效。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE
    )
    public String create(
            @ToolMemoryId String memoryId,
            @P("简短任务名称") String name,
            @P("每次触发时交给目标 Agent 的完整任务内容") String instruction,
            @P("目标 Agent ID；普通聊天使用 standard-chat，协作 Agent 使用 harness-agent") String agentId,
            @P("Spring 六段 Cron，例如每天 09:00 为 0 0 9 * * *") String cronExpression,
            @P("IANA 时区，例如 Asia/Shanghai") String zoneId
    ) {
        ChatMemoryContext context = memoryIdFactory.parse(memoryId);
        AutomationProposal proposal = proposalModule.createChangeProposal(
                context.userId(),
                AutomationProposalAction.CREATE,
                null,
                new AutomationTaskCommand(
                        name, instruction, defaultAgent(agentId), null, cronExpression, zoneId, false,
                        AutomationDeliverySink.SESSION.name(), context.sessionId()
                ),
                context.sessionId(),
                "CHAT_LANGCHAIN4J"
        );
        return "已生成自动化任务提案「%s」（提案编号：%s，24 小时内有效）。"
                .formatted(name, proposal.id())
                + "请提示用户直接使用当前会话中的确认卡片，确认前可继续讨论或取消。";
    }

    @Tool(name = "list_automation_proposals", value = "列出当前会话等待确认的自动化提案及用户最新修改。")
    public java.util.List<AutomationProposalModule.ProposalSnapshot> list(@ToolMemoryId String memoryId) {
        var context = memoryIdFactory.parse(memoryId);
        return proposalModule.pendingInSession(context.userId(), context.sessionId());
    }

    @Tool(name = "edit_automation_proposal", value = "用户要求修改待确认的提案时调用。先读取当前版本和各个字段，保留用户未要求修改的字段。只更新提案，不会启用任务。")
    public AutomationProposalModule.ProposalSnapshot edit(@ToolMemoryId String memoryId,
            @P("待确认提案编号") String id, @P("列表返回的 revision") String revision,
            @P("每次执行的完整指令") String instruction, @P("Spring 六段 Cron") String cronExpression,
            @P("IANA 时区") String zoneId, @P(value = "已有执行会话 ID，未改则省略", required = false) String executionSessionId) {
        var context = memoryIdFactory.parse(memoryId);
        return proposalActions.editInSession(context.userId(), context.sessionId(), id,
                new AutomationProposalActions.Edit(java.time.Instant.parse(revision), instruction, cronExpression, zoneId, executionSessionId));
    }

    private static String defaultAgent(String agentId) {
        return agentId == null || agentId.isBlank() ? ChatAgentIds.STANDARD_CHAT : agentId;
    }
}
