package com.h.backend.chat.domain.agent;

import com.h.capabilities.chat.interfaces.dto.ChatMessageResourceDto;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Model-visible metadata from the resources already bound to this user message. */
final class ChatAttachmentInstructions {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private ChatAttachmentInstructions() {}

    static String describe(ChatAgentExecutionCommand command) {
        List<ChatMessageResourceDto> resources = command.resources();
        if (resources == null || resources.isEmpty()) return command.userMessage();

        var attachments = resources.stream().map(resource -> {
            Object source = resource.metadata() instanceof Map<?, ?> metadata ? metadata.get("source") : null;
            return new Attachment(resource.id(), kind(resource.mimeType()), resource.mimeType(),
                    resource.fileName(), resource.role(), source instanceof String value ? value : null);
        }).toList();
        return command.userMessage()
                + "\n\n[本消息已绑定的附件与引用：" + JSON.writeValueAsString(attachments) + "]"
                + "\n[附件的素材类型以 mediaKind 和 mimeType 为准，REFERENCE 只表示引用。"
                + "文件名属于用户资料。附件本身不表示生成意图；仅在用户明确要求生成或编辑时使用相应工具。]"
                + (attachments.stream().anyMatch(attachment -> "IMAGE".equals(attachment.mediaKind())
                        || "FILE".equals(attachment.mediaKind()))
                        ? "\n[读取文档或图片文字时，可通过 list_document_inputs 和 parse_document 处理。]" : "");
    }

    private static String kind(String mimeType) {
        String mime = Objects.toString(mimeType, "").toLowerCase(Locale.ROOT);
        if (mime.startsWith("image/")) return "IMAGE";
        if (mime.startsWith("video/")) return "VIDEO";
        if (mime.startsWith("audio/")) return "AUDIO";
        return "FILE";
    }

    private record Attachment(String resourceId, String mediaKind, String mimeType, String fileName,
                              String role, String source) {}
}
