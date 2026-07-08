package com.h.backend.chat.infrastructure.tools;

import com.h.backend.chat.infrastructure.filesystem.AssistantFileStorage;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

@Component
public class FilesystemTool {

    private final AssistantFileStorage fileStorage;

    public FilesystemTool(AssistantFileStorage fileStorage) {
        this.fileStorage = fileStorage;
    }

    @Tool(name = "read_file", value = "读取当前会话文件目录中的文本文件，支持按行分页。", searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String readFile(
            @ToolMemoryId String memoryId,
            @P("当前会话内的文件路径，例如 /a.txt 或 /notes/a.txt") String path,
            @P(value = "起始行，0 表示从第一行开始", required = false, defaultValue = "0") int offset,
            @P(value = "最多返回行数，0 表示全部返回", required = false, defaultValue = "0") int limit
    ) {
        return fileStorage.read(memoryId, path, offset, limit);
    }

    @Tool(name = "write_file", value = "在当前会话文件目录中写入新文件；如果文件已存在会失败。", searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String writeFile(
            @ToolMemoryId String memoryId,
            @P("当前会话内的目标文件路径，例如 /a.txt 或 /notes/a.txt") String path,
            @P("要写入的文本内容") String content
    ) {
        return fileStorage.write(memoryId, path, content);
    }

    @Tool(name = "edit_file", value = "对当前会话文件目录中的文件执行精确字符串替换。", searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String editFile(
            @ToolMemoryId String memoryId,
            @P("要编辑的虚拟路径") String path,
            @P("要查找的精确文本") String oldString,
            @P("替换后的文本") String newString,
            @P(value = "是否替换全部命中；false 时 old_string 必须唯一", required = false, defaultValue = "false") boolean replaceAll
    ) {
        return fileStorage.edit(memoryId, path, oldString, newString, replaceAll);
    }

    @Tool(name = "list_files", value = "列出当前会话文件目录中的文件和目录。", searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String listFiles(
            @ToolMemoryId String memoryId,
            @P(value = "当前会话内要列出的目录，默认 /", required = false, defaultValue = "/") String path
    ) {
        return fileStorage.list(memoryId, path);
    }

    @Tool(name = "delete_file", value = "删除当前会话文件目录中的文件或目录；删除目录时必须 recursive=true。", searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String deleteFile(
            @ToolMemoryId String memoryId,
            @P("要删除的虚拟路径") String path,
            @P(value = "是否递归删除目录", required = false, defaultValue = "false") boolean recursive
    ) {
        return fileStorage.delete(memoryId, path, recursive);
    }

    @Tool(name = "move_file", value = "移动或重命名当前会话文件目录中的文件或目录。", searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String moveFile(
            @ToolMemoryId String memoryId,
            @P("来源虚拟路径") String fromPath,
            @P("目标虚拟路径") String toPath,
            @P(value = "目标存在时是否覆盖", required = false, defaultValue = "false") boolean overwrite
    ) {
        return fileStorage.move(memoryId, fromPath, toPath, overwrite);
    }
}
