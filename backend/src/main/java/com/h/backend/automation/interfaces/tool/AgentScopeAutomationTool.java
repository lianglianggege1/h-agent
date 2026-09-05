package com.h.backend.automation.interfaces.tool;

import com.h.backend.automation.application.AutomationTaskCommand;
import com.h.backend.automation.application.AutomationTaskService;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class AgentScopeAutomationTool {

    private final AutomationTaskService taskService;

    public AgentScopeAutomationTool(@Lazy AutomationTaskService taskService) {
        this.taskService = taskService;
    }

    @Tool(
            name = "create_automation_task",
            description = "创建周期性自动化任务。仅当用户明确要求定时、每天、每周或周期执行时调用。Cron 使用六段格式（秒 分 时 日 月 周），时区使用 IANA 名称。",
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
        AutomationTask task = taskService.create(Long.valueOf(context.getUserId()), new AutomationTaskCommand(
                name, instruction, ChatAgentIds.HARNESS, null, cronExpression, zoneId, true
        ), "CHAT_AGENTSCOPE");
        return "自动化任务已创建：%s（ID：%s，下次执行：%s）"
                .formatted(task.name(), task.id(), task.nextRunAt());
    }
}
