package com.h.backend.observability;

import com.h.agent.observability.semantic.ArtifactKind;
import com.h.agent.observability.semantic.ArtifactReference;
import com.h.agent.observability.semantic.ArtifactReferenceBlock;
import com.h.agent.observability.semantic.ArtifactUse;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.generation.domain.model.GeneratedArtifact;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BusinessArtifactReferenceMapper 契约（设计 §9.4）：纯映射、不泄漏存储定位信息、
 * 映射失败不抛异常。
 */
class BusinessArtifactReferenceMapperTest {

    @Test
    void mapsChatMessageResourceDtoWithAllBusinessFields() {
        ChatMessageResourceDto dto = new ChatMessageResourceDto(
                "res-1", "IMAGE", "ATTACHMENT",
                "/api/chat/resources/res-1/content", "/api/chat/resources/res-1/download",
                "photo.png", "image/png", 2048L, 640, 480,
                "minio", "resources/2026/08/res-1"
        );

        ArtifactReference reference =
                BusinessArtifactReferenceMapper.from(dto, ArtifactUse.MODEL_INPUT, "res-0");

        assertThat(reference.resourceId()).isEqualTo("res-1");
        assertThat(reference.sourceResourceId()).isEqualTo("res-0");
        assertThat(reference.kind()).isEqualTo(ArtifactKind.IMAGE);
        assertThat(reference.use()).isEqualTo(ArtifactUse.MODEL_INPUT);
        assertThat(reference.businessRole()).isEqualTo("ATTACHMENT");
        assertThat(reference.mimeType()).isEqualTo("image/png");
        assertThat(reference.byteSize()).isEqualTo(2048L);
        assertThat(reference.width()).isEqualTo(640);
        assertThat(reference.height()).isEqualTo(480);
        assertThat(reference.fileName()).isEqualTo("photo.png");
        assertThat(reference.applicationViewUrl()).isEqualTo("/api/chat/resources/res-1/content");
    }

    @Test
    void neverLeaksStorageLocation() {
        ChatMessageResourceDto dto = new ChatMessageResourceDto(
                "res-2", "FILE", "ATTACHMENT", null, null,
                "a.bin", "application/octet-stream", 1L, null, null,
                "minio", "resources/secret/key"
        );
        StoredResource stored = new StoredResource(
                "res-3", "minio", "resources/secret/key2", "video/mp4", "clip.mp4", 9L, null, null);

        ArtifactReference fromDto = BusinessArtifactReferenceMapper.from(dto, ArtifactUse.SOURCE, null);
        ArtifactReference fromStored = BusinessArtifactReferenceMapper.from(
                stored, "GENERATED", ArtifactUse.TOOL_OUTPUT, "/api/chat/resources/res-3/content");

        String dtoJson = referenceJson(fromDto);
        String storedJson = referenceJson(fromStored);
        assertThat(dtoJson).doesNotContain("minio", "storage", "secret", "key", "bucket", "endpoint");
        assertThat(storedJson).doesNotContain("minio", "storage", "secret", "key", "bucket", "endpoint");
    }

    @Test
    void mapsGeneratedArtifactWithGeneratedRoleAndViewUrl() {
        GeneratedArtifact artifact = new GeneratedArtifact(
                "res-9", "minio", "resources/gen/res-9", "video/mp4", "out.mp4", 123_456L);

        ArtifactReference reference = BusinessArtifactReferenceMapper.from(
                artifact, ArtifactUse.TOOL_OUTPUT, "/api/chat/resources/res-9/content");

        assertThat(reference.resourceId()).isEqualTo("res-9");
        assertThat(reference.kind()).isEqualTo(ArtifactKind.VIDEO);
        assertThat(reference.businessRole()).isEqualTo("GENERATED");
        assertThat(reference.byteSize()).isEqualTo(123_456L);
        assertThat(reference.fileName()).isEqualTo("out.mp4");
        assertThat(reference.applicationViewUrl()).isEqualTo("/api/chat/resources/res-9/content");
    }

    @Test
    void mapsStoredResourceForCommittedAttachmentSeam() {
        StoredResource stored = new StoredResource(
                "res-4", "minio", "key", "audio/mpeg", "voice.mp3", 88L, null, null);

        ArtifactReference reference = BusinessArtifactReferenceMapper.from(
                stored, "GENERATED", ArtifactUse.MODEL_OUTPUT, null);

        assertThat(reference.resourceId()).isEqualTo("res-4");
        assertThat(reference.kind()).isEqualTo(ArtifactKind.AUDIO);
        assertThat(reference.businessRole()).isEqualTo("GENERATED");
        assertThat(reference.byteSize()).isEqualTo(88L);
        assertThat(reference.applicationViewUrl()).isNull();
    }

    @Test
    void normalizesKindFromTypeThenMimeThenFileFallback() {
        assertThat(BusinessArtifactReferenceMapper.normalizeKind("document", null))
                .isEqualTo(ArtifactKind.DOCUMENT);
        assertThat(BusinessArtifactReferenceMapper.normalizeKind(null, "image/webp"))
                .isEqualTo(ArtifactKind.IMAGE);
        assertThat(BusinessArtifactReferenceMapper.normalizeKind(null, "audio/wav"))
                .isEqualTo(ArtifactKind.AUDIO);
        assertThat(BusinessArtifactReferenceMapper.normalizeKind("unknown", "application/pdf"))
                .isEqualTo(ArtifactKind.FILE);
        assertThat(BusinessArtifactReferenceMapper.normalizeKind(null, null))
                .isEqualTo(ArtifactKind.FILE);
    }

    @Test
    void mappingFailuresReturnNullInsteadOfThrowing() {
        assertThat(BusinessArtifactReferenceMapper.from((ChatMessageResourceDto) null,
                ArtifactUse.MODEL_INPUT, null)).isNull();
        assertThat(BusinessArtifactReferenceMapper.from(new ChatMessageResourceDto(
                        " ", "IMAGE", "ATTACHMENT", null, null, null, null, null, null, null),
                ArtifactUse.MODEL_INPUT, null)).isNull();
        assertThat(BusinessArtifactReferenceMapper.from(
                (GeneratedArtifact) null, ArtifactUse.TOOL_OUTPUT, null)).isNull();
        assertThat(BusinessArtifactReferenceMapper.from(
                (StoredResource) null, "GENERATED", ArtifactUse.TOOL_OUTPUT, null)).isNull();
    }

    @Test
    void referenceBlocksSkipsFailedEntriesAndKeepsOrder() {
        ChatMessageResourceDto ok1 = new ChatMessageResourceDto(
                "res-a", "IMAGE", "ATTACHMENT", "u1", "d1", "1.png", "image/png", 1L, null, null);
        ChatMessageResourceDto broken = new ChatMessageResourceDto(
                null, "IMAGE", "ATTACHMENT", null, null, null, null, null, null, null);
        ChatMessageResourceDto ok2 = new ChatMessageResourceDto(
                "res-b", "DOCUMENT", "REFERENCE", "u2", "d2", "2.pdf", "application/pdf", 2L, null, null);

        List<ArtifactReferenceBlock> blocks = BusinessArtifactReferenceMapper.referenceBlocks(
                java.util.Arrays.asList(ok1, broken, ok2), ArtifactUse.MODEL_INPUT);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).reference().resourceId()).isEqualTo("res-a");
        assertThat(blocks.get(1).reference().resourceId()).isEqualTo("res-b");
        assertThat(blocks.get(1).reference().kind()).isEqualTo(ArtifactKind.DOCUMENT);
        assertThat(BusinessArtifactReferenceMapper.referenceBlocks(null, ArtifactUse.MODEL_INPUT))
                .isEmpty();
        assertThat(BusinessArtifactReferenceMapper.referenceBlocks(
                List.of(), ArtifactUse.MODEL_INPUT)).isEmpty();
    }

    private static String referenceJson(ArtifactReference reference) {
        StringBuilder sb = new StringBuilder();
        append(reference, sb);
        return sb.toString();
    }

    private static void append(ArtifactReference r, StringBuilder sb) {
        sb.append(r.resourceId()).append('|')
                .append(r.sourceResourceId()).append('|')
                .append(r.kind()).append('|')
                .append(r.use()).append('|')
                .append(r.businessRole()).append('|')
                .append(r.mimeType()).append('|')
                .append(r.byteSize()).append('|')
                .append(r.width()).append('|')
                .append(r.height()).append('|')
                .append(r.fileName()).append('|')
                .append(r.applicationViewUrl());
    }
}
