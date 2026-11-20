package com.h.otheragents.team.tool.springai;

import com.h.backend.chat.infrastructure.tools.ShellExecutionService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Spring AI 原生 Shell 工具入口：以 ToolContext 中的 memoryId 定位会话目录，委托 ShellExecutionService。 */
public class SpringShellTools {

    private final ShellExecutionService shell;

    public SpringShellTools(ShellExecutionService shell) {
        this.shell = shell;
    }

    @Tool(name = "execute_shell", description = "在当前会话文件目录中执行 shell 命令。可用于处理会话文件、运行脚本和查看命令输出。")
    public String executeShell(
            ToolContext toolContext,
            @ToolParam(description = "要执行的 shell 命令") String command,
            @ToolParam(required = false, description = "当前会话内的工作目录，例如 / 或 /project") String workingDirectory,
            @ToolParam(required = false, description = "超时时间，单位秒；0 表示使用默认值") Integer timeoutSeconds) {
        String memoryId = SpringToolContext.from(toolContext).memoryId();
        return shell.execute(memoryId, command,
                workingDirectory == null || workingDirectory.isBlank() ? "/" : workingDirectory,
                timeoutSeconds == null ? 0 : timeoutSeconds);
    }
}