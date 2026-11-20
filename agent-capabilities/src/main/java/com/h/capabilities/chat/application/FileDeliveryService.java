package com.h.capabilities.chat.application;

import com.h.agent.observability.semantic.ArtifactUse;
import com.h.agent.observability.semantic.ToolArtifactCollector;
import com.h.capabilities.seam.DeliveredArtifact;
import com.h.capabilities.seam.DeliveryResult;
import com.h.capabilities.seam.port.ArtifactDeliveryPort;
import com.h.capabilities.seam.port.LiveProjectionPort;
import com.h.capabilities.chat.infrastructure.content.ResourceContentInspector;
import com.h.capabilities.chat.infrastructure.filesystem.AssistantFileStorage;
import com.h.capabilities.chat.infrastructure.filesystem.AssistantFileStorage.AssistantSessionFileStream;
import com.h.capabilities.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.capabilities.document.infrastructure.persistence.DocumentRepository;
import com.h.capabilities.observability.BusinessArtifactReferenceMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

/**
 * Agent 文件交付业务服务（框架无关）：把会话文件经签名校验后交付到聊天。
 *
 * <p>内容安全（新计划 §6.3 / §10 任务 4）：Agent 模型生成的会话文件属于
 * 不可信输入——模型可声明任意 MIME，因此保存前必须经签名校验
 * （Inspector 只读有上限的文件头，校验后用回放流继续保存，不二次读源流）；
 * 校验失败返回 Error 文案（Agent 工具协议），不抛异常破坏会话循环。
 * 各框架的工具入口只做薄委托。
 */
@Service
public class FileDeliveryService {

    @Autowired(required = false)
    private DocumentRepository documentTasks;

    private final AssistantFileStorage fileStorage;
    private final ArtifactDeliveryPort delivery;
    private final LiveProjectionPort projection;
    private final ResourceContentInspector contentInspector;
    private final ResourceContentPolicy contentPolicy;

    public FileDeliveryService(
            AssistantFileStorage fileStorage,
            ArtifactDeliveryPort delivery,
            LiveProjectionPort projection,
            ResourceContentInspector contentInspector,
            ResourceContentPolicy contentPolicy
    ) {
        this.fileStorage = fileStorage;
        this.delivery = delivery;
        this.projection = projection;
        this.contentInspector = contentInspector;
        this.contentPolicy = contentPolicy;
    }

    public String send(String memoryId, String path, String displayName, String mimeType, String message) {
        AssistantSessionFileStream file = fileStorage.openSessionFileStream(memoryId, path);
        if (!file.success()) {
            return "Error: " + file.error();
        }

        FileDeliveryContext context = parseMemoryId(memoryId);
        String resolvedFileName = safeDisplayName(displayName, file.fileName());
        // generate_document already commits its file message. A model may create a
        // slightly different copy and try to send it again; scope reuse to this turn.
        try {
            if (documentTasks != null) {
                Long anchor = documentTasks.activeAnchor(context.userId(), context.sessionId());
                if (anchor != null) {
                    var delivered = documentTasks.list(context.userId(), context.sessionId()).stream()
                            .filter(task -> "GENERATE".equals(task.getKind())
                                    && "COMPLETED".equals(task.getStatus())
                                    && anchor.equals(task.getAnchorMessageId())
                                    && resolvedFileName.equals(task.getFileName())
                                    && task.getOutputResourceId() != null)
                            .findFirst();
                    if (delivered.isPresent()) {
                        closeQuietly(file.stream());
                        return "该文档已自动发送到聊天中，请复用已有文件，不要再次复制或发送。resourceId="
                                + delivered.get().getOutputResourceId();
                    }
                }
            }
        } catch (RuntimeException failure) {
            closeQuietly(file.stream());
            throw failure;
        }
        String resolvedMimeType = resolveMimeType(mimeType, file.mimeType(), resolvedFileName);
        String extension = extensionFromFileName(resolvedFileName);
        String resourceType = resourceTypeFor(resolvedMimeType);
        String content = (message == null || message.isBlank()) ? "已发送文件：" + resolvedFileName : message.trim();

        // 内容安全（新计划 §6.3 / §10 任务 4）：模型声明 MIME 只是提示，
        // 签名校验失败/主动内容一律拒绝交付，不触碰 Coordinator。
        // 审查修复第 4 项：inspect 抛 IOException 时必须关闭源流（fd 泄漏）。
        InputStream sourceStream = file.stream();
        ResourceContentInspector.Inspection inspection;
        try {
            inspection = contentInspector.inspect(sourceStream, resolvedMimeType);
        } catch (IOException exception) {
            closeQuietly(sourceStream);
            return "Error: 读取文件失败: " + file.virtualPath();
        }
        ResourceContentPolicy.SaveDecision decision =
                contentPolicy.validateForSave(inspection.result(), resolvedMimeType);
        if (!decision.allowed()) {
            closeQuietly(inspection.replayStream());
            return "Error: " + decision.reason();
        }

        // 新计划任务 3：Agent 文件只有交付挂接事务提交后才算挂接；
        // onAttached 回调在挂接事务内执行（rollback 时对象被补偿），
        // 流事件在 deliver 返回（挂接事务已提交）后才发布。
        // 保存流使用回放流（头字节已缓冲 + 剩余原流），不二次读源。
        DeliveredArtifact[] delivered = new DeliveredArtifact[1];
        DeliveryResult result = delivery.deliver(
                context.userId(),
                context.sessionId(),
                ResourceSaveCommand.fromStream(
                        resourceType,
                        inspection.replayStream(),
                        file.size(),
                        resolvedMimeType,
                        extension,
                        0L
                ),
                resourceType,
                resolvedFileName,
                resolvedMimeType,
                content,
                artifact -> delivered[0] = artifact
        );
        // Artifact 在挂接事务提交后才成立（设计 §9.5）：deliver 正常返回即提交，
        // 此处只做已持有业务结果的纯映射，进入所属工具 Observation 的 TOOL_OUTPUT。
        ToolArtifactCollector.record(BusinessArtifactReferenceMapper.from(
                delivered[0], ArtifactUse.TOOL_OUTPUT, null));
        projection.publish(memoryId, result.projectionHandle());
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

    private static void closeQuietly(InputStream stream) {
        try {
            if (stream != null) {
                stream.close();
            }
        } catch (IOException ignored) {
            // 关闭失败不影响拒绝路径
        }
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