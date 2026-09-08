package com.h.backend.automation.interfaces.tool;

import com.h.backend.automation.application.AutomationProposalModule;
import com.h.backend.automation.application.AutomationTaskCommand;
import com.h.backend.automation.domain.AutomationDeliverySink;
import com.h.backend.automation.domain.AutomationProposal;
import com.h.backend.automation.domain.AutomationProposalAction;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.domain.memory.ChatMemoryContext;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class LangChain4jAutomationTool {

    private final AutomationProposalModule proposalModule;
    private final ChatMemoryIdFactory memoryIdFactory;

    public LangChain4jAutomationTool(
            @Lazy AutomationProposalModule proposalModule,
            ChatMemoryIdFactory memoryIdFactory
    ) {
        this.proposalModule = proposalModule;
        this.memoryIdFactory = memoryIdFactory;
    }

    @Tool(
            name = "create_automation_task",
            value = "创建周期性自动化任务的提案。仅当用户明确要求定时、每天、每周或周期执行时调用。"
                    + "使用 Spring 六段 Cron（秒 分 时 日 月 周）并明确 IANA 时区。"
                    + "该工具只会生成提案，不会直接创建或开启任务：必须提示用户到自动化管理页确认后才生效。",
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
                + "请告知用户：提案不会自动生效，需要在自动化管理页确认后才会创建，确认前可随时取消。";
    }

    private static String defaultAgent(String agentId) {
        return agentId == null || agentId.isBlank() ? ChatAgentIds.STANDARD_CHAT : agentId;
    }
}
