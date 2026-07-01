package com.h.backend.chat.interfaces.web;

import com.h.backend.chat.infrastructure.config.ResourceUploadProperties;
import com.h.backend.chat.interfaces.dto.ResourceUploadResponse;
import com.h.backend.chat.infrastructure.persistence.entity.ChatMessageResourceEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMessageResourceMapper;
import com.h.backend.chat.application.ChatResourceService;
import com.h.backend.chat.infrastructure.storage.ResourceSaveCommand;
import com.h.backend.chat.infrastructure.storage.ResourceStorage;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

    private final ChatResourceService chatResourceService;
    private final ResourceStorage resourceStorage;
    private final ChatMessageResourceMapper chatMessageResourceMapper;
    private final ResourceUploadProperties uploadProperties;

    public ChatResourceController(
            ChatResourceService chatResourceService,
            ResourceStorage resourceStorage,
            ChatMessageResourceMapper chatMessageResourceMapper,
            ResourceUploadProperties uploadProperties
    ) {
        this.chatResourceService = chatResourceService;
        this.resourceStorage = resourceStorage;
        this.chatMessageResourceMapper = chatMessageResourceMapper;
        this.uploadProperties = uploadProperties;
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ResourceUploadResponse> upload(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "sessionId", required = false) String sessionId,
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
        StoredResource stored = resourceStorage.save(new ResourceSaveCommand(
                resourceType, null, null, file.getBytes(), mimeType, extension, null, null
        ));

        String originalName = safeFileName(file.getOriginalFilename(), extension);
        ChatMessageResourceEntity row = new ChatMessageResourceEntity();
        row.setId(stored.id());
        row.setMessageId(null);
        row.setUserId(principal.userId());
        row.setSessionId(sessionId);
        row.setResourceType(resourceType);
        row.setResourceRole(resourceRole);
        row.setStorageType(stored.storageType());
        row.setStorageKey(stored.storageKey());
        row.setViewUrl(resourceStorage.buildViewUrl(stored.id()));
        row.setDownloadUrl(resourceStorage.buildDownloadUrl(stored.id()));
        row.setMimeType(stored.mimeType());
        row.setFileName(originalName);
        row.setFileSize(stored.fileSize());
        row.setWidth(stored.width());
        row.setHeight(stored.height());
        row.setCreatedAt(LocalDateTime.now());
        chatMessageResourceMapper.insert(row);

        return ResponseEntity.ok(new ResourceUploadResponse(
                stored.id(), resourceType, resourceRole,
                resourceStorage.buildViewUrl(stored.id()),
                resourceStorage.buildDownloadUrl(stored.id()),
                originalName, stored.mimeType(), stored.fileSize()
        ));
    }

    @GetMapping("/{resourceId}/content")
    public ResponseEntity<InputStreamResource> preview(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String resourceId
    ) {
        return toResponse(chatResourceService.openPreview(principal.userId(), resourceId));
    }

    @GetMapping("/{resourceId}/download")
    public ResponseEntity<InputStreamResource> download(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable String resourceId
    ) {
        return toResponse(chatResourceService.openDownload(principal.userId(), resourceId));
    }

    private ResponseEntity<InputStreamResource> toResponse(ChatResourceService.ResourceResponse resource) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.parseMediaType(resource.content().mimeType()));
        headers.setContentLength(resource.content().fileSize());
        if (resource.attachment()) {
            headers.setContentDisposition(ContentDisposition.attachment()
                    .filename(resource.fileName(), StandardCharsets.UTF_8)
                    .build());
        }
        return ResponseEntity.ok()
                .headers(headers)
                .body(new InputStreamResource(resource.content().inputStream()));
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
