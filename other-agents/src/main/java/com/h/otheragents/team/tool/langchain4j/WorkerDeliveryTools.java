package com.h.otheragents.team.tool.langchain4j;

import com.h.backend.chat.application.FileDeliveryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;

/** LangChain4j 原生文件交付工具入口：以框架注入的 memoryId 定位会话文件，委托 FileDeliveryService。 */
public class WorkerDeliveryTools {

    private final FileDeliveryService delivery;

    public WorkerDeliveryTools(FileDeliveryService delivery) {
        this.delivery = delivery;
    }

    @Tool(name = "send_file_to_chat", value = "把当前会话文件目录中的文件发送到聊天框，用户可预览或下载。"
            + "generate_document生成的文档已自动发送，不要复制或重复发送。",
            searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String sendFileToChat(
            @ToolMemoryId String memoryId,
            @P("当前会话内的文件路径，例如 /report.pptx 或 /audio/demo.mp3") String path,
            @P(value = "聊天中显示的文件名；为空则使用原文件名", required = false, defaultValue = "") String displayName,
            @P(value = "文件 MIME 类型；为空则自动推断", required = false, defaultValue = "") String mimeType,
            @P(value = "随文件消息显示的文本；为空则使用默认文案", required = false, defaultValue = "") String message) {
        return delivery.send(memoryId, path, displayName, mimeType, message);
    }
}