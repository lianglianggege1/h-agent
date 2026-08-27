package com.h.backend.chat.infrastructure.tools;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ChatResourceUrls;
import com.h.backend.chat.application.ChatStreamEventBridge;
import com.h.backend.chat.infrastructure.filesystem.AssistantFileStorage;
import com.h.backend.chat.infrastructure.filesystem.AssistantFileStorage.AssistantSessionFileStream;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.SearchBehavior;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolMemoryId;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

@Component
public class FileDeliveryTool {

    private final AssistantFileStorage fileStorage;
    private final ResourceWriteCoordinator writeCoordinator;
    private final ChatSessionService chatSessionService;
    private final ChatStreamEventBridge chatStreamEventBridge;
    private final ChatResourceUrls chatResourceUrls;

    public FileDeliveryTool(
            AssistantFileStorage fileStorage,
            ResourceWriteCoordinator writeCoordinator,
            ChatSessionService chatSessionService,
            ChatStreamEventBridge chatStreamEventBridge,
            ChatResourceUrls chatResourceUrls
    ) {
        this.fileStorage = fileStorage;
        this.writeCoordinator = writeCoordinator;
        this.chatSessionService = chatSessionService;
        this.chatStreamEventBridge = chatStreamEventBridge;
        this.chatResourceUrls = chatResourceUrls;
    }

    @Tool(name = "send_file_to_chat", value = "把当前会话文件目录中的文件发送到聊天框，用户可预览或下载。", searchBehavior = SearchBehavior.ALWAYS_VISIBLE)
    public String sendFileToChat(
            @ToolMemoryId String memoryId,
            @P("当前会话内的文件路径，例如 /report.pptx 或 /audio/demo.mp3") String path,
            @P(value = "聊天中显示的文件名；为空则使用原文件名", required = false, defaultValue = "") String displayName,
            @P(value = "文件 MIME 类型；为空则自动推断", required = false, defaultValue = "") String mimeType,
            @P(value = "随文件消息显示的文本；为空则使用默认文案", required = false, defaultValue = "") String message
    ) {
        AssistantSessionFileStream file = fileStorage.openSessionFileStream(memoryId, path);
        if (!file.success()) {
            return "Error: " + file.error();
        }

        FileDeliveryContext context = parseMemoryId(memoryId);
        String resolvedFileName = safeDisplayName(displayName, file.fileName());
        String resolvedMimeType = resolveMimeType(mimeType, file.mimeType(), resolvedFileName);
        String extension = extensionFromFileName(resolvedFileName);
        String resourceType = resourceTypeFor(resolvedMimeType);
        String content = (message == null || message.isBlank()) ? "已发送文件：" + resolvedFileName : message.trim();

        // 新计划任务 3：Agent 文件只有 appendResourceMessage 事务提交后才算挂接；
        // 挂接回调在 Coordinator 事务内（rollback 时对象被补偿），
        // 流事件在 saveAndAttach 返回（挂接事务已提交）后才发布。
        // 模型提供的 MIME 只作提示传给命令，不能作为可信响应类型（内容校验在任务 4）。
        ChatSessionMessageDto chatMessage = writeCoordinator.saveAndAttach(
                ResourceSaveCommand.fromStream(
                        resourceType,
                        file.stream(),
                        file.size(),
                        resolvedMimeType,
                        extension,
                        0L
                ),
                stored -> {
                    ChatMessageResourceDto resource = new ChatMessageResourceDto(
                            stored.id(),
                            resourceType,
                            "GENERATED",
                            chatResourceUrls.view(stored.id()),
                            chatResourceUrls.download(stored.id()),
                            resolvedFileName,
                            resolvedMimeType,
                            stored.fileSize(),
                            stored.width(),
                            stored.height(),
                            stored.storageType(),
                            stored.storageKey()
                    );
                    return chatSessionService.appendResourceMessage(
                            context.userId(),
                            context.sessionId(),
                            content,
                            resourceType,
                            List.of(resource)
                    );
                }
        );
        chatStreamEventBridge.publishMessage(memoryId, chatMessage);
        return "文件已发送到聊天中。";
    }

    private FileDeliveryContext parseMemoryId(String memoryId) {
        String[] parts = memoryId == null ? new String[0] : memoryId.split(":", 4);
        if (parts.length == 3) {
            return new FileDeliveryContext(Long.valueOf(parts[0]), parts[2]);
        }
        if (parts.length == 4 && "agent".equals(parts[1])) {
            return new FileDeliveryContext(Long.valueOf(parts[0]), parts[3]);
        }
        throw new IllegalArgumentException("Invalid chat memory id");
    }

    private String safeDisplayName(String displayName, String fallbackName) {
        String value = displayName == null || displayName.isBlank() ? fallbackName : displayName;
        if (value == null || value.isBlank()) {
            value = "file";
        }
        return value.replaceAll("[\\r\\n\\\\/]", "_");
    }

    private String resolveMimeType(String requestedMimeType, String probedMimeType, String fileName) {
        if (requestedMimeType != null && !requestedMimeType.isBlank()) {
            return requestedMimeType.trim();
        }
        if (probedMimeType != null && !probedMimeType.isBlank()) {
            return probedMimeType;
        }
        return mimeTypeFromExtension(extensionFromFileName(fileName));
    }

    private String mimeTypeFromExtension(String extension) {
        return switch (extension.toLowerCase(Locale.ROOT)) {
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pdf" -> "application/pdf";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "doc" -> "application/msword";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "xls" -> "application/vnd.ms-excel";
            case "txt" -> "text/plain";
            case "md" -> "text/markdown";
            case "mp3" -> "audio/mpeg";
            case "m4a" -> "audio/mp4";
            case "wav" -> "audio/wav";
            case "webm" -> "audio/webm";
            case "mp4" -> "video/mp4";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "webp" -> "image/webp";
            default -> "application/octet-stream";
        };
    }

    private String extensionFromFileName(String fileName) {
        if (fileName == null) {
            return "bin";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "bin";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String resourceTypeFor(String mimeType) {
        String normalized = mimeType == null ? "" : mimeType.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("image/")) {
            return "IMAGE";
        }
        if (normalized.startsWith("audio/")) {
            return "AUDIO";
        }
        if (normalized.startsWith("video/")) {
            return "VIDEO";
        }
        return "FILE";
    }

    private record FileDeliveryContext(Long userId, String sessionId) {
    }
}
