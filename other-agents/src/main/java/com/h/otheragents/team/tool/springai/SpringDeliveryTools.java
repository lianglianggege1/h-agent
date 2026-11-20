package com.h.otheragents.team.tool.springai;

import com.h.capabilities.chat.application.FileDeliveryService;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/** Spring AI 原生文件交付工具入口：以 ToolContext 中的 memoryId 定位会话文件，委托 FileDeliveryService。 */
public class SpringDeliveryTools {

    private final FileDeliveryService delivery;

    public SpringDeliveryTools(FileDeliveryService delivery) {
        this.delivery = delivery;
    }

    @Tool(name = "send_file_to_chat", description = "把当前会话文件目录中的文件发送到聊天框，用户可预览或下载。"
            + "generate_document生成的文档已自动发送，不要复制或重复发送。")
    public String sendFileToChat(
            ToolContext toolContext,
            @ToolParam(description = "当前会话内的文件路径，例如 /report.pptx 或 /audio/demo.mp3") String path,
            @ToolParam(required = false, description = "聊天中显示的文件名；为空则使用原文件名") String displayName,
            @ToolParam(required = false, description = "文件 MIME 类型；为空则自动推断") String mimeType,
            @ToolParam(required = false, description = "随文件消息显示的文本；为空则使用默认文案") String message) {
        return delivery.send(SpringToolContext.from(toolContext).memoryId(), path, displayName, mimeType, message);
    }
}