package com.h.backend.observability;

import com.h.agent.observability.semantic.ArtifactKind;
import com.h.agent.observability.semantic.ArtifactReference;
import com.h.agent.observability.semantic.ArtifactReferenceBlock;
import com.h.agent.observability.semantic.ArtifactUse;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.generation.domain.model.GeneratedArtifact;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 业务对象到 {@link ArtifactReference} 的纯映射（设计 §9.4）。
 *
 * <p>约束：不依赖 ResourceStorage/MinIO SDK/Repository/HTTP Client，不调用 open/stat
 * 验证对象存在，不解析任意 metadata_json；storageType、storageKey、bucket、endpoint、
 * 凭据与预签名 URL 一律不进入映射结果。映射失败（缺少 resourceId 等）返回 null 或跳过，
 * 不向业务抛异常。</p>
 */
public final class BusinessArtifactReferenceMapper {

    private BusinessArtifactReferenceMapper() {
    }

    /** ChatMessageResourceDto + ArtifactUse -> ArtifactReference（applicationViewUrl 取 dto.viewUrl）。 */
    public static ArtifactReference from(ChatMessageResourceDto dto, ArtifactUse use, String sourceResourceId) {
        if (dto == null || blank(dto.id())) {
            return null;
        }
        return ArtifactReference.builder()
                .resourceId(dto.id())
                .sourceResourceId(blankToNull(sourceResourceId))
                .kind(normalizeKind(dto.type(), dto.mimeType()))
                .use(use)
                .businessRole(dto.role())
                .mimeType(dto.mimeType())
                .byteSize(dto.fileSize())
                .width(dto.width())
                .height(dto.height())
                .fileName(dto.fileName())
                .applicationViewUrl(dto.viewUrl())
                .build();
    }

    /** GeneratedArtifact + ArtifactUse + applicationViewUrl -> ArtifactReference。 */
    public static ArtifactReference from(GeneratedArtifact artifact, ArtifactUse use, String applicationViewUrl) {
        if (artifact == null || blank(artifact.resourceId())) {
            return null;
        }
        return ArtifactReference.builder()
                .resourceId(artifact.resourceId())
                .kind(normalizeKind(null, artifact.mimeType()))
                .use(use)
                .businessRole("GENERATED")
                .mimeType(artifact.mimeType())
                .byteSize(artifact.fileSize())
                .fileName(artifact.fileName())
                .applicationViewUrl(blankToNull(applicationViewUrl))
                .build();
    }

    /** StoredResource + 已提交业务挂接结果 -> ArtifactReference。 */
    public static ArtifactReference from(StoredResource stored, String businessRole, ArtifactUse use,
                                         String applicationViewUrl) {
        if (stored == null || blank(stored.id())) {
            return null;
        }
        return ArtifactReference.builder()
                .resourceId(stored.id())
                .kind(normalizeKind(null, stored.mimeType()))
                .use(use)
                .businessRole(businessRole)
                .mimeType(stored.mimeType())
                .byteSize(stored.fileSize())
                .width(stored.width())
                .height(stored.height())
                .fileName(stored.fileName())
                .applicationViewUrl(blankToNull(applicationViewUrl))
                .build();
    }

    /** 列表映射：跳过失败项；非空输入全部失败时返回空列表，由调用方决定 CAPTURE_ERROR 语义。 */
    public static List<ArtifactReferenceBlock> referenceBlocks(List<ChatMessageResourceDto> resources, ArtifactUse use) {
        if (resources == null || resources.isEmpty()) {
            return List.of();
        }
        List<ArtifactReferenceBlock> blocks = new ArrayList<>(resources.size());
        for (ChatMessageResourceDto resource : resources) {
            ArtifactReference reference = from(resource, use, null);
            if (reference != null) {
                blocks.add(new ArtifactReferenceBlock(reference));
            }
        }
        return blocks;
    }

    /** 业务 type 归一：优先资源 type，其次 MIME 前缀，兜底 FILE。 */
    static ArtifactKind normalizeKind(String type, String mimeType) {
        String normalizedType = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
        switch (normalizedType) {
            case "IMAGE":
                return ArtifactKind.IMAGE;
            case "VIDEO":
                return ArtifactKind.VIDEO;
            case "AUDIO":
                return ArtifactKind.AUDIO;
            case "DOCUMENT":
                return ArtifactKind.DOCUMENT;
            case "FILE":
                return ArtifactKind.FILE;
            default:
        }
        String normalizedMime = mimeType == null ? "" : mimeType.trim().toLowerCase(Locale.ROOT);
        if (normalizedMime.startsWith("image/")) {
            return ArtifactKind.IMAGE;
        }
        if (normalizedMime.startsWith("video/")) {
            return ArtifactKind.VIDEO;
        }
        if (normalizedMime.startsWith("audio/")) {
            return ArtifactKind.AUDIO;
        }
        return ArtifactKind.FILE;
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(String value) {
        return blank(value) ? null : value;
    }
}
