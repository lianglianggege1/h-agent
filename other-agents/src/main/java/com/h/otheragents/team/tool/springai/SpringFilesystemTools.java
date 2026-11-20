package com.h.otheragents.team.tool.springai;

import com.h.capabilities.chat.infrastructure.filesystem.AssistantFileStorage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Spring AI 原生会话文件工具入口：以 ToolContext 中的 memoryId 定位会话目录，委托 AssistantFileStorage。 */
public class SpringFilesystemTools {

    private final AssistantFileStorage fileStorage;

    public SpringFilesystemTools(AssistantFileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Tool(name = "read_file", description = "读取当前会话文件目录中的文本文件，支持按行分页。")
    public String readFile(
            ToolContext toolContext,
            @ToolParam(description = "当前会话内的文件路径，例如 /a.txt 或 /notes/a.txt") String path,
            @ToolParam(required = false, description = "起始行，0 表示从第一行开始") Integer offset,
            @ToolParam(required = false, description = "最多返回行数，0 表示全部返回") Integer limit) {
        String memoryId = SpringToolContext.from(toolContext).memoryId();
        return fileStorage.read(memoryId, path, orZero(offset), orZero(limit));
    }

    @Tool(name = "write_file", description = "在当前会话文件目录中写入新文件；如果文件已存在会失败。")
    public String writeFile(
            ToolContext toolContext,
            @ToolParam(description = "当前会话内的目标文件路径，例如 /a.txt 或 /notes/a.txt") String path,
            @ToolParam(description = "要写入的文本内容") String content) {
        return fileStorage.write(SpringToolContext.from(toolContext).memoryId(), path, content);
    }

    @Tool(name = "edit_file", description = "对当前会话文件目录中的文件执行精确字符串替换。")
    public String editFile(
            ToolContext toolContext,
            @ToolParam(description = "要编辑的虚拟路径") String path,
            @ToolParam(description = "要查找的精确文本") String oldString,
            @ToolParam(description = "替换后的文本") String newString,
            @ToolParam(required = false, description = "是否替换全部命中；false 时 old_string 必须唯一") Boolean replaceAll) {
        return fileStorage.edit(SpringToolContext.from(toolContext).memoryId(), path, oldString, newString,
                Boolean.TRUE.equals(replaceAll));
    }

    @Tool(name = "list_files", description = "列出当前会话文件目录中的文件和目录。")
    public String listFiles(
            ToolContext toolContext,
            @ToolParam(required = false, description = "当前会话内要列出的目录，默认 /") String path) {
        return fileStorage.list(SpringToolContext.from(toolContext).memoryId(),
                path == null || path.isBlank() ? "/" : path);
    }

    @Tool(name = "delete_file", description = "删除当前会话文件目录中的文件或目录；删除目录时必须 recursive=true。")
    public String deleteFile(
            ToolContext toolContext,
            @ToolParam(description = "要删除的虚拟路径") String path,
            @ToolParam(required = false, description = "是否递归删除目录") Boolean recursive) {
        return fileStorage.delete(SpringToolContext.from(toolContext).memoryId(), path, Boolean.TRUE.equals(recursive));
    }

    @Tool(name = "move_file", description = "移动或重命名当前会话文件目录中的文件或目录。")
    public String moveFile(
            ToolContext toolContext,
            @ToolParam(description = "来源虚拟路径") String fromPath,
            @ToolParam(description = "目标虚拟路径") String toPath,
            @ToolParam(required = false, description = "目标存在时是否覆盖") Boolean overwrite) {
        return fileStorage.move(SpringToolContext.from(toolContext).memoryId(), fromPath, toPath,
                Boolean.TRUE.equals(overwrite));
    }

    private static int orZero(Integer value) {
        return value == null ? 0 : value;
    }
}