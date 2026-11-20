package com.h.otheragents.team.tool.langchain4j;

import com.h.capabilities.chat.infrastructure.tools.ShellExecutionService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;

/** LangChain4j 原生 Shell 工具入口：以框架注入的 memoryId 定位会话目录，委托 ShellExecutionService。 */
public class WorkerShellTools {

    private final ShellExecutionService shell;

    public WorkerShellTools(ShellExecutionService shell) {
        this.shell = shell;
    }

    @Tool(name = "execute_shell", value = "在当前会话文件目录中执行 shell 命令。可用于处理会话文件、运行脚本和查看命令输出。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String executeShell(
            @ToolMemoryId String memoryId,
            @P("要执行的 shell 命令") String command,
            @P(value = "当前会话内的工作目录，例如 / 或 /project", required = false, defaultValue = "/") String workingDirectory,
            @P(value = "超时时间，单位秒；0 表示使用默认值", required = false, defaultValue = "0") int timeoutSeconds) {
        return shell.execute(memoryId, command, workingDirectory, timeoutSeconds);
    }
}