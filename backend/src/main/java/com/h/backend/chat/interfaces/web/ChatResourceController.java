package com.h.backend.chat.interfaces.web;

import com.h.backend.chat.application.ChatResourceService;
import com.h.backend.chat.application.ChatResourceUrls;
import com.h.backend.chat.application.ResourceContentPolicy;
import com.h.backend.chat.infrastructure.config.ResourceUploadProperties;
import com.h.backend.chat.infrastructure.content.ResourceContentInspector;
import com.h.backend.chat.interfaces.dto.ResourceUploadResponse;
import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.infrastructure.storage.ResourceContent;
import com.h.backend.chat.infrastructure.storage.ResourceRange;
import com.h.backend.chat.infrastructure.storage.ResourceRangeException;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceWriteCoordinator;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

@RestController
@RequestMapping("/api/chat/resources")
public class ChatResourceController {

    /** 所有资源响应统一携带的反 MIME 嗅探头（计划 §6.3）。 */
    static final String X_CONTENT_TYPE_OPTIONS_HEADER = "X-Content-Type-Options";
    static final String X_CONTENT_TYPE_OPTIONS_NOSNIFF = "nosniff";

    private final ChatResourceService chatResourceService;
    private final ResourceWriteCoordinator writeCoordinator;
    private final ChatMessageResourceMapper chatMessageResourceMapper;
    private final ResourceUploadProperties uploadProperties;
    private final ChatResourceUrls chatResourceUrls;
    private final ResourceContentInspector contentInspector;
    private final ResourceContentPolicy contentPolicy;

    public ChatResourceController(
            ChatResourceService chatResourceService,
            ResourceWriteCoordinator writeCoordinator,
            ChatMessageResourceMapper chatMessageResourceMapper,
            ResourceUploadProperties uploadProperties,
            ChatResourceUrls chatResourceUrls,
            ResourceContentInspector contentInspector,
            ResourceContentPolicy contentPolicy
    ) {
        this.chatResourceService = chatResourceService;
        this.writeCoordinator = writeCoordinator;
        this.chatMessageResourceMapper = chatMessageResourceMapper;
        this.uploadProperties = uploadProperties;
        this.chatResourceUrls = chatResourceUrls;
        this.contentInspector = contentInspector;
        this.contentPolicy = contentPolicy;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResourceUploadResponse> upload(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "role", required = false, defaultValue = "ATTACHMENT") String role
    ) throws IOException {
        String mimeType = file.getContentType();
        if (mimeType == null || !uploadProperties.getAllowedMimeTypes().contains(mimeType)) {
            throw new BusinessException(40000, "暂不支持该文件类型: " + mimeType);
        }
        if (file.getSize() > uploadProperties.getMaxFileSize()) {
            long maxMb = uploadProperties.getMaxFileSize() / 1_048_576;
            throw new BusinessException(40000, "文件大小不能超过 " + maxMb + "MB");
        }

        String resourceType = mimeType.startsWith("image/") ? "IMAGE"
                : mimeType.startsWith("video/") ? "VIDEO"
                : mimeType.startsWith("audio/") ? "AUDIO" : "FILE";
        String resourceRole = normalizeRole(role);

        String extension = extensionForMimeType(mimeType);
        String originalName = safeFileName(file.getOriginalFilename(), extension);

        // 内容安全（新计划 §6.3 / §10 任务 4）：用户上传属于不可信输入，
        // 保存前必须通过签名校验；Inspector 只读有上限的文件头，
        // 校验通过后用回放流（已读头字节 + 剩余原流）继续保存，
        // 不二次读源流、不整读 byte[]。
        ResourceContentInspector.Inspection inspection =
                contentInspector.inspect(file.getInputStream(), mimeType);
        ResourceContentPolicy.SaveDecision decision =
                contentPolicy.validateForSave(inspection.result(), mimeType);
        if (!decision.allowed()) {
            inspection.replayStream().close();
            throw new BusinessException(40000, decision.reason());
        }

        // 新计划任务 3：写入经 Coordinator，对象先落对象存储，
        // DB insert 在挂接回调内执行（挂接事务 rollback 时对象被 best-effort 补偿删除）。
        ResourceUploadResponse response = writeCoordinator.saveAndAttach(
                ResourceSaveCommand.fromStream(
                        resourceType,
                        inspection.replayStream(),
                        file.getSize(),
                        mimeType,
                        extension,
                        uploadProperties.getMaxFileSize()
                ),
                stored -> {
                    ChatMessageResourceEntity row = new ChatMessageResourceEntity();
                    row.setId(stored.id());
                    row.setMessageId(null);
                    row.setUserId(principal.userId());
                    row.setResourceType(resourceType);
                    row.setResourceRole(resourceRole);
                    row.setStorageType(stored.storageType());
                    row.setStorageKey(stored.storageKey());
                    row.setViewUrl(chatResourceUrls.view(stored.id()));
                    row.setDownloadUrl(chatResourceUrls.download(stored.id()));
                    row.setMimeType(stored.mimeType());
                    row.setFileName(originalName);
                    row.setFileSize(stored.fileSize());
                    row.setWidth(stored.width());
                    row.setHeight(stored.height());
                    row.setCreatedAt(LocalDateTime.now());
                    chatMessageResourceMapper.insert(row);
                    return new ResourceUploadResponse(
                            stored.id(), resourceType, resourceRole,
                            chatResourceUrls.view(stored.id()),
                            chatResourceUrls.download(stored.id()),
                            originalName, stored.mimeType(), stored.fileSize()
                    );
                }
        );

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{resourceId}/content")
    public ResponseEntity<InputStreamResource> preview(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String resourceId,
            @RequestHeader(value = HttpHeaders.RANGE, required = false) String rangeHeader
    ) {
        ResourceRange range = parseRangeHeader(rangeHeader);
        try {
            return toContentResponse(chatResourceService.openPreview(principal.userId(), resourceId, range));
        } catch (ResourceRangeException exception) {
            if (exception.reason() == ResourceRangeException.Reason.UNSATISFIABLE) {
                // 合法但不可满足：416 + Content-Range: bytes */total（计划 §6.4）。
                // 在 Controller 层直接构建响应，不扩展 GlobalExceptionHandler——
                // Content-Range 头需要 totalSize，BusinessException 链路不携带该信息，
                // 这是最小改动方案。
                HttpHeaders headers = new HttpHeaders();
                headers.set(HttpHeaders.CONTENT_RANGE, "bytes */" + exception.totalSize());
                return ResponseEntity.status(HttpStatus.REQUESTED_RANGE_NOT_SATISFIABLE)
                        .headers(headers)
                        .build();
            }
            throw new BusinessException(40000, "Range 请求头格式无效");
        }
    }

    @GetMapping("/{resourceId}/download")
    public ResponseEntity<InputStreamResource> download(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String resourceId
    ) {
        return toContentResponse(chatResourceService.openDownload(principal.userId(), resourceId));
    }

    private ResourceRange parseRangeHeader(String rangeHeader) {
        if (rangeHeader == null || rangeHeader.isBlank()) {
            return ResourceRange.fullRead();
        }
        try {
            return ResourceRange.fromHeader(rangeHeader);
        } catch (ResourceRangeException exception) {
            throw new BusinessException(40000, "Range 请求头格式无效");
        }
    }

    /**
     * 统一内容响应构造（新计划 §6.4/§11.3）：
     * <ul>
     *   <li>Content-Type 使用策略输出（未知 → application/octet-stream）；</li>
     *   <li>所有响应（content 与 download、200 与 206）都带
     *       {@code X-Content-Type-Options: nosniff} 与 {@code Accept-Ranges: bytes}；</li>
     *   <li>按 {@code ResourceContent.partial} 判定 206/200：206 携带
     *       {@code Content-Range: bytes offset-(offset+length-1)/total}；</li>
     *   <li>attachment=true（download 恒真；content 非白名单强制真）时输出
     *       {@code Content-Disposition: attachment} 带文件名。</li>
     * </ul>
     */
    private ResponseEntity<InputStreamResource> toContentResponse(ChatResourceService.ResourceResponse resource) {
        ResourceContent content = resource.content();
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(resource.responseContentType()));
        headers.setContentLength(content.responseLength());
        headers.set(HttpHeaders.ACCEPT_RANGES, "bytes");
        headers.set(X_CONTENT_TYPE_OPTIONS_HEADER, X_CONTENT_TYPE_OPTIONS_NOSNIFF);
        if (content.partial()) {
            headers.set(HttpHeaders.CONTENT_RANGE, "bytes %d-%d/%d".formatted(
                    content.offset(), content.offset() + content.responseLength() - 1, content.totalSize()));
        }
        if (resource.attachment()) {
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(resource.fileName(), StandardCharsets.UTF_8)
                    .build());
        }
        HttpStatus status = content.partial() ? HttpStatus.PARTIAL_CONTENT : HttpStatus.OK;
        return ResponseEntity.status(status)
                .headers(headers)
                .body(new InputStreamResource(content.inputStream()));
    }

    private String extensionForMimeType(String mimeType) {
        if ("image/jpeg".equalsIgnoreCase(mimeType)) return "jpg";
        if ("image/png".equalsIgnoreCase(mimeType)) return "png";
        if ("image/webp".equalsIgnoreCase(mimeType)) return "webp";
        if ("video/mp4".equalsIgnoreCase(mimeType)) return "mp4";
        if ("audio/mpeg".equalsIgnoreCase(mimeType)) return "mp3";
        if ("audio/mp4".equalsIgnoreCase(mimeType)) return "m4a";
        if ("audio/wav".equalsIgnoreCase(mimeType)) return "wav";
        if ("audio/webm".equalsIgnoreCase(mimeType)) return "webm";
        return "bin";
    }

    private String safeFileName(String name, String extension) {
        if (name == null || name.isBlank()) {
            return "upload." + extension;
        }
        return name.replaceAll("[\\r\\n\\\\/]", "_");
    }

    private String normalizeRole(String role) {
        String normalized = role == null ? "ATTACHMENT" : role.trim().toUpperCase();
        if ("ATTACHMENT".equals(normalized) || "REFERENCE".equals(normalized) || "GENERATED".equals(normalized)) {
            return normalized;
        }
        throw new BusinessException(40000, "暂不支持该资源角色: " + role);
    }
}
