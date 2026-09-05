package com.h.backend.automation.interfaces.tool;

import com.h.backend.automation.application.AutomationTaskCommand;
import com.h.backend.automation.application.AutomationTaskService;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

@Component
public class LangChain4jAutomationTool {

    private final AutomationTaskService taskService;

    public LangChain4jAutomationTool(@Lazy AutomationTaskService taskService) {
        this.taskService = taskService;
    }

    @Tool(
            name = "create_automation_task",
            value = "创建周期性自动化任务。仅当用户明确要求定时、每天、每周或周期执行时调用。使用 Spring 六段 Cron（秒 分 时 日 月 周）并明确 IANA 时区；任务创建成功后返回任务 ID 和下次执行时间。",
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
        Long userId = userIdFromMemory(memoryId);
        AutomationTask task = taskService.create(userId, new AutomationTaskCommand(
                name, instruction, defaultAgent(agentId), null, cronExpression, zoneId, true
        ), "CHAT_LANGCHAIN4J");
        return "自动化任务已创建：%s（ID：%s，运行时：%s，下次执行：%s）"
                .formatted(task.name(), task.id(), task.runtime(), task.nextRunAt());
    }

    private static Long userIdFromMemory(String memoryId) {
        String[] parts = memoryId == null ? new String[0] : memoryId.split(":", 4);
        if (parts.length < 3) {
            throw new IllegalArgumentException("Invalid chat memory id");
        }
        return Long.valueOf(parts[0]);
    }

    private static String defaultAgent(String agentId) {
        return agentId == null || agentId.isBlank() ? ChatAgentIds.STANDARD_CHAT : agentId;
    }
}
