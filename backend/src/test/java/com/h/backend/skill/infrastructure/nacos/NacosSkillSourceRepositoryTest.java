package com.h.backend.skill.infrastructure.nacos;

import com.alibaba.nacos.api.ai.model.skills.Skill;
import com.alibaba.nacos.api.ai.model.skills.SkillMeta;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.maintainer.client.ai.SkillMaintainerService;
import com.h.backend.skill.domain.SkillFileSet;
import com.h.backend.skill.domain.SkillPlatformException;
import com.h.backend.skill.domain.SkillPlatformErrorKind;
import com.h.backend.skill.domain.tar.DeterministicSkillTarBuilder;
import com.h.backend.skill.domain.tar.SkillTarReader;
import com.h.backend.skill.infrastructure.config.SkillPlatformProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NacosSkillSourceRepositoryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final SkillMaintainerService client = mock(SkillMaintainerService.class);
    private final SkillPlatformProperties properties = new SkillPlatformProperties();
    private NacosSkillSourceRepository repository;
    private Skill current;
    private SkillMeta meta;
    private SkillMeta.SkillVersionSummary summary;

    @BeforeEach
    void setUp() throws Exception {
        properties.getNacos().setNamespace("isolated-test");
        repository = new NacosSkillSourceRepository(properties, new DeterministicSkillTarBuilder(json),
                new SkillTarReader(json), json, client);
        when(client.getSkillMeta(anyString(), anyString())).thenAnswer(inv -> {
            if (meta == null) throw new NacosException(404, "not found");
            return meta;
        });
        when(client.createDraft(anyString(), anyString(), isNull(), any(), any(), anyString())).thenAnswer(inv -> {
            meta = new SkillMeta();
            meta.setScope("PUBLIC");
            meta.setEditingVersion("v1");
            summary = new SkillMeta.SkillVersionSummary();
            summary.setVersion("v1");
            summary.setStatus("draft");
            meta.setVersions(List.of(summary));
            current = json.readValue(inv.getArgument(4, String.class), Skill.class);
            return "v1";
        });
        when(client.updateScope(anyString(), anyString(), eq("PRIVATE"))).thenAnswer(inv -> { meta.setScope("PRIVATE"); return true; });
        when(client.updateDraft(anyString(), anyString(), eq(false), anyString())).thenAnswer(inv -> {
            assertThat(meta.getScope()).isEqualTo("PRIVATE");
            current = json.readValue(inv.getArgument(1, String.class), Skill.class);
            // Reproduce server frontmatter normalization without altering resource payloads.
            current.setSkillMd(current.getSkillMd().replace("description: Managed by H Agent", "description: Managed by H Agent\nversion: v1"));
            return true;
        });
        when(client.getSkillVersionDetail(anyString(), anyString(), eq("v1"))).thenAnswer(inv -> {
            if (current == null) throw new NacosException(404, "not found");
            return current;
        });
        when(client.submit(anyString(), anyString(), eq("v1"))).thenAnswer(inv -> { summary.setStatus("online"); meta.setEditingVersion(null); return "v1"; });
    }

    private SkillFileSet files(String md) {
        return SkillFileSet.of(Map.of("SKILL.md", md.getBytes(StandardCharsets.UTF_8),
                "assets/pixel.png", new byte[]{0, -1, 2, 10}, "references/empty.txt", new byte[0]));
    }

    @Test
    void preservesOriginalFrontmatterBinaryAndEmptyFilesAcrossServerNormalization() {
        var original = files("---\r\nname: user-name\r\ndescription: 中文\r\n---\r\n  text\r\n\n");
        var draft = repository.openDraft(11, original);
        assertThat(draft.files().paths()).containsExactlyInAnyOrderElementsOf(original.paths());
        original.files().forEach((path, bytes) -> assertThat(draft.files().get(path)).isEqualTo(bytes));
        assertThat(current.getSkillMd()).contains("name: h-agent-skill-11", "version: v1");
        assertThat(draft.revision()).isEqualTo(DeterministicSkillTarBuilder.sha256Hex(new DeterministicSkillTarBuilder(json).build(original)));
    }

    @Test
    void supportsTenMiBOfIncompressibleAssetsWithoutOversizedIndividualResources() {
        Map<String, byte[]> files = new java.util.LinkedHashMap<>();
        Random random = new Random(7);
        for (int i = 0; i < 10; i++) { byte[] bytes = new byte[1024 * 1024]; random.nextBytes(bytes); files.put("assets/image-" + i + ".png", bytes); }
        var draft = repository.openDraft(11, SkillFileSet.of(files));
        files.forEach((path, bytes) -> assertThat(draft.files().get(path)).isEqualTo(bytes));
        assertThat(current.getResource().values()).allSatisfy(r -> assertThat(r.getContent().length()).isLessThan(2 * 1024 * 1024));
    }

    @Test
    void retriesCreationAndRecoversUnregisteredForkSeed() throws Exception {
        var first = repository.openDraft(11, files("one"));
        assertThat(repository.openDraft(11, files("one")).revision()).isEqualTo(first.revision());
        verify(client, times(1)).createDraft(anyString(), anyString(), any(), any(), any(), any());
        assertThat(repository.openDraft(11, files("different")).files().requireText("SKILL.md")).isEqualTo("different");
        verify(client, times(2)).updateDraft(anyString(), anyString(), any(), anyString());
    }

    @Test
    void recordsParentAndAcceptsOnlyExactLostResponseSaveRetry() {
        var before = repository.openDraft(11, files("one"));
        var saved = repository.save(11, "v1", before.revision(), files("two"));
        assertThat(saved.parentRevision()).isEqualTo(before.revision());
        assertThat(repository.save(11, "v1", before.revision(), files("two")).revision()).isEqualTo(saved.revision());
        assertThatThrownBy(() -> repository.save(11, "v1", before.revision(), files("three")))
                .isInstanceOfSatisfying(SkillPlatformException.class, ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.PROPOSAL_HEAD_MISMATCH));
    }

    @Test
    void rejectsTamperedSourceFileAndReadableMirror() {
        repository.openDraft(11, files("one"));
        current.setSkillMd(current.getSkillMd() + "external edit");
        assertThatThrownBy(() -> repository.read(11, "v1")).isInstanceOf(SkillPlatformException.class);
        repository = new NacosSkillSourceRepository(properties, new DeterministicSkillTarBuilder(json), new SkillTarReader(json), json, client);
        current.setSkillMd(current.getSkillMd().replace("external edit", ""));
        current.getResource().values().stream().filter(r -> r.getName().equals("original-skill.md")).findFirst().orElseThrow().setContent("AAAA");
        assertThatThrownBy(() -> repository.read(11, "v1")).isInstanceOfSatisfying(SkillPlatformException.class,
                ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.SOURCE_DRIFTED));
    }

    @Test
    void publishesFixedVersionIdempotentlyWithoutForceOrDefaultLatestReads() throws Exception {
        var draft = repository.openDraft(11, files("one"));
        repository.publish(11, "v1", draft.revision());
        repository.publish(11, "v1", draft.revision());
        verify(client, times(1)).submit("isolated-test", "h-agent-skill-11", "v1");
        verify(client, never()).forcePublish(anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> repository.save(11, "v1", draft.revision(), files("two"))).isInstanceOf(SkillPlatformException.class);
        assertThatThrownBy(() -> repository.discard(11, "v1", draft.revision())).isInstanceOf(SkillPlatformException.class);
    }

    @Test
    void pendingReviewNeverForcesPublication() throws Exception {
        var draft = repository.openDraft(11, files("one"));
        when(client.submit(anyString(), anyString(), anyString())).thenAnswer(inv -> { summary.setStatus("reviewing"); return "v1"; });
        assertThatThrownBy(() -> repository.publish(11, "v1", draft.revision())).isInstanceOf(SkillPlatformException.class);
        verify(client, never()).forcePublish(anyString(), anyString(), anyString(), any());
        summary.setStatus("reviewed");
        when(client.publish(anyString(), anyString(), eq("v1"), eq(false))).thenAnswer(inv -> { summary.setStatus("online"); return true; });
        repository.publish(11, "v1", draft.revision());
        verify(client).publish("isolated-test", "h-agent-skill-11", "v1", false);
    }

    @Test
    void forksWithExplicitBaseBecauseTheHttpFormRejectsAnImplicitLatest() throws Exception {
        var first = repository.openDraft(11, files("one"));
        repository.publish(11, first.version(), first.revision());
        when(client.createDraft(eq("isolated-test"), eq("h-agent-skill-11"), eq("v1"), isNull(), isNull(), anyString()))
                .thenAnswer(inv -> {
                    meta.setEditingVersion("v2");
                    var next = new SkillMeta.SkillVersionSummary();
                    next.setVersion("v2"); next.setStatus("draft");
                    meta.setVersions(List.of(summary, next));
                    return "v2";
                });
        when(client.getSkillVersionDetail(anyString(), anyString(), eq("v2"))).thenAnswer(inv -> current);
        var fork = repository.openDraft(11, files("different seed"));
        assertThat(fork.version()).isEqualTo("v2");
        assertThat(fork.files().requireText("SKILL.md")).isEqualTo("different seed");
        verify(client).createDraft(eq("isolated-test"), eq("h-agent-skill-11"), eq("v1"), isNull(), isNull(), anyString());
    }

    @Test
    void historicalImportRetriesSameContentWithoutCreatingAnotherVersion() throws Exception {
        var first = repository.importPublished(11, "v1", files("one"));
        assertThat(repository.importPublished(11, "v1", files("one")).version()).isEqualTo(first.version());
        verify(client, times(1)).createDraft(anyString(), anyString(), any(), any(), any(), any());
        verify(client, times(1)).submit(anyString(), anyString(), anyString());
    }

    @Test
    void staleDiscardCannotDeleteAWriteThatReachedNacosBeforePg() throws Exception {
        var first = repository.openDraft(11, files("one"));
        repository.save(11, "v1", first.revision(), files("newer"));
        assertThatThrownBy(() -> repository.discard(11, "v1", first.revision()))
                .isInstanceOfSatisfying(SkillPlatformException.class, ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.PROPOSAL_HEAD_MISMATCH));
        verify(client, never()).deleteDraft(anyString(), anyString());
    }

    @Test
    void refusesMissingOrLatestVersionRatherThanDownloadingMutableContent() throws Exception {
        for (String version : new String[]{null, "", "latest"}) {
            assertThatThrownBy(() -> repository.read(11, version)).isInstanceOf(SkillPlatformException.class);
        }
        verify(client, never()).getSkillVersionDetail(anyString(), anyString(), any());
    }

    @Test
    void rejectedPipelineWithReviewedVersionNeverAttemptsPublish() throws Exception {
        var draft = repository.openDraft(11, files("one"));
        summary.setStatus("reviewed");
        summary.setPublishPipelineInfo("{\"status\":\"REJECTED\"}");
        assertThatThrownBy(() -> repository.publish(11, "v1", draft.revision()))
                .isInstanceOfSatisfying(SkillPlatformException.class, ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.OPERATION_CONFLICT));
        verify(client, never()).publish(anyString(), anyString(), anyString(), any());
        verify(client, never()).forcePublish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void waitsForVerifiedTargetInsteadOfAcceptingStaleReadAfterSave() throws Exception {
        var draft = repository.openDraft(11, files("one"));
        Skill previous = current;
        var stale = new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(inv -> {
            current = json.readValue(inv.getArgument(1, String.class), Skill.class);
            stale.set(true);
            return true;
        }).when(client).updateDraft(anyString(), anyString(), eq(false), anyString());
        when(client.getSkillVersionDetail(anyString(), anyString(), eq("v1"))).thenAnswer(inv ->
                stale.compareAndSet(true, false) ? previous : current);
        var saved = repository.save(11, "v1", draft.revision(), files("two"));
        assertThat(saved.files().requireText("SKILL.md")).isEqualTo("two");
        assertThat(saved.parentRevision()).isEqualTo(draft.revision());
    }

    @Test
    void rejectedReviewedVersionCanBeEditedAndRevalidatedWithoutForcePublish() throws Exception {
        var draft = repository.openDraft(11, files("one"));
        summary.setStatus("reviewed");
        summary.setPublishPipelineInfo("{\"status\":\"REJECTED\"}");
        meta.setEditingVersion(null); meta.setReviewingVersion("v1");
        when(client.redraft("isolated-test", "h-agent-skill-11", "v1")).thenAnswer(inv -> {
            summary.setStatus("draft");
            summary.setPublishPipelineInfo("{\"status\":\"REJECTED\",\"historical\":true}");
            meta.setEditingVersion("v1"); meta.setReviewingVersion(null);
            return true;
        });
        var edited = repository.save(11, "v1", draft.revision(), files("fixed"));
        assertThat(edited.files().requireText("SKILL.md")).isEqualTo("fixed");
        repository.publish(11, "v1", edited.revision());
        verify(client).redraft("isolated-test", "h-agent-skill-11", "v1");
        verify(client, never()).forcePublish(anyString(), anyString(), anyString(), any());
    }

    @Test
    void refusesPublicSourcesAndSanitizesCredentialErrors() throws Exception {
        repository.openDraft(11, files("one"));
        meta.setScope("PUBLIC");
        assertThatThrownBy(() -> repository.read(11, "v1")).isInstanceOf(SkillPlatformException.class);
        when(client.getSkillMeta(anyString(), anyString())).thenThrow(new NacosException(403, "secret password=do-not-leak"));
        assertThatThrownBy(() -> repository.openDraft(12, files("one")))
                .isInstanceOf(SkillPlatformException.class).hasMessageNotContaining("do-not-leak");
    }
}
