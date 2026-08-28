package com.h.backend.skill.application;

import com.h.backend.skill.domain.ArtifactDescriptor;
import com.h.backend.skill.domain.SkillFileSet;
import com.h.backend.skill.domain.SkillPlatformErrorKind;
import com.h.backend.skill.domain.SkillPlatformException;
import com.h.backend.skill.domain.tar.SkillBundleManifest;
import com.h.backend.skill.infrastructure.artifact.SkillArtifactResolver;
import com.h.backend.skill.infrastructure.artifact.VerifiedSkillBundle;
import com.h.backend.skill.infrastructure.config.SkillPlatformProperties;
import com.h.backend.skill.infrastructure.persistence.entity.AgentRunSkillBindingEntity;
import com.h.backend.skill.infrastructure.persistence.entity.SkillDefinitionEntity;
import com.h.backend.skill.infrastructure.persistence.entity.SkillReleaseEntity;
import com.h.backend.skill.infrastructure.persistence.mapper.AgentRunSkillBindingMapper;
import com.h.backend.skill.infrastructure.persistence.mapper.SkillDefinitionMapper;
import com.h.backend.skill.infrastructure.persistence.mapper.SkillReleaseMapper;
import dev.langchain4j.skills.Skills;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillRuntimeServiceTest {

    private static final long USER_ID = 7L;
    private static final long RUN_ID = 99L;
    private static final String MEMORY_ID = "7:1:session-1";

    private final SkillDefinitionMapper definitionMapper = mock(SkillDefinitionMapper.class);
    private final SkillReleaseMapper releaseMapper = mock(SkillReleaseMapper.class);
    private final AgentRunSkillBindingMapper bindingMapper = mock(AgentRunSkillBindingMapper.class);
    private final SkillArtifactResolver artifactResolver = mock(SkillArtifactResolver.class);
    private final SkillPlatformProperties properties = new SkillPlatformProperties();

    private SkillRuntimeService service;

    @BeforeEach
    void setUp() {
        properties.getSystemSkills().clear();
        service = new SkillRuntimeService(
                definitionMapper, releaseMapper, bindingMapper, artifactResolver, properties);
    }

    private static SkillDefinitionEntity enabledSkill(long id, String key, long activeReleaseId) {
        SkillDefinitionEntity definition = new SkillDefinitionEntity();
        definition.setId(id);
        definition.setOwnerUserId(USER_ID);
        definition.setSkillKey(key);
        definition.setDisplayName("Display " + key);
        definition.setEnabled(true);
        definition.setActiveReleaseId(activeReleaseId);
        return definition;
    }

    private static SkillReleaseEntity availableRelease(long id, int version) {
        SkillReleaseEntity release = new SkillReleaseEntity();
        release.setId(id);
        release.setSkillId(11L);
        release.setVersionNumber(version);
        release.setStatus(SkillReleaseEntity.STATUS_AVAILABLE);
        release.setArtifactMediaType(ArtifactDescriptor.MEDIA_TYPE);
        release.setArtifactDigest("sha256:" + "a".repeat(64));
        release.setArtifactSize(1234L);
        release.setArtifactStore(ArtifactDescriptor.USER_STORE);
        release.setArtifactObjectKey("v1/users/7/blobs/sha256/aa/aaa.skill.tar");
        return release;
    }

    private static VerifiedSkillBundle bundleFor(ArtifactDescriptor descriptor) {
        SkillFileSet files = SkillFileSet.of(Map.of(
                "SKILL.md", """
                        ---
                        name: demo-skill
                        description: demo
                        ---

                        body""".getBytes(),
                "skill.yaml", "key: demo-skill".getBytes(),
                "references/guide.md", "guide".getBytes()));
        SkillBundleManifest manifest = new SkillBundleManifest(1, List.of(
                new SkillBundleManifest.Entry("SKILL.md", 30, "aa"),
                new SkillBundleManifest.Entry("skill.yaml", 16, "bb"),
                new SkillBundleManifest.Entry("references/guide.md", 5, "cc")));
        return new VerifiedSkillBundle(descriptor, files, manifest);
    }

    @Test
    void snapshotLoadsEnabledUserSkillAndPersistsBinding() {
        SkillDefinitionEntity definition = enabledSkill(11L, "demo-skill", 21L);
        SkillReleaseEntity release = availableRelease(21L, 3);
        when(definitionMapper.selectSnapshotCandidates(USER_ID)).thenReturn(List.of(definition));
        when(releaseMapper.selectBySkillAndId(11L, 21L)).thenReturn(release);
        ArtifactDescriptor descriptor = new ArtifactDescriptor(
                1, release.getArtifactMediaType(), release.getArtifactDigest(),
                release.getArtifactSize(), release.getArtifactStore(),
                release.getArtifactObjectKey(), null);
        when(artifactResolver.openVerified(descriptor)).thenReturn(bundleFor(descriptor));

        SkillRuntimeService.PreparedSnapshot snapshot =
                service.snapshotForTopLevelRun(USER_ID, RUN_ID, MEMORY_ID);

        assertThat(snapshot.skills()).hasSize(1);
        assertThat(snapshot.skills().get(0).skillKey()).isEqualTo("demo-skill");
        assertThat(snapshot.skills().get(0).releaseId()).isEqualTo(21L);
        assertThat(snapshot.isEmpty()).isFalse();
        assertThat(snapshot.langchainSkills()).isNotNull();

        ArgumentCaptor<AgentRunSkillBindingEntity> captor =
                ArgumentCaptor.forClass(AgentRunSkillBindingEntity.class);
        verify(bindingMapper).insert(captor.capture());
        AgentRunSkillBindingEntity binding = captor.getValue();
        assertThat(binding.getRunId()).isEqualTo(RUN_ID);
        assertThat(binding.getSkillKey()).isEqualTo("demo-skill");
        assertThat(binding.getArtifactDigest()).isEqualTo(release.getArtifactDigest());
        assertThat(binding.getSourceType()).isEqualTo(AgentRunSkillBindingEntity.SOURCE_USER);
    }

    @Test
    void snapshotRegistersByMemoryIdAndServesSystemMessage() {
        SkillDefinitionEntity definition = enabledSkill(11L, "demo-skill", 21L);
        SkillReleaseEntity release = availableRelease(21L, 1);
        when(definitionMapper.selectSnapshotCandidates(USER_ID)).thenReturn(List.of(definition));
        when(releaseMapper.selectBySkillAndId(11L, 21L)).thenReturn(release);
        when(artifactResolver.openVerified(any(ArtifactDescriptor.class)))
                .thenAnswer(invocation -> bundleFor(invocation.getArgument(0)));

        service.snapshotForTopLevelRun(USER_ID, RUN_ID, MEMORY_ID);

        assertThat(service.findPrepared(MEMORY_ID)).isNotNull();
        String systemMessage = service.skillsSystemMessage(MEMORY_ID);
        assertThat(systemMessage).contains("demo-skill").contains("activate_skill");
        assertThat(service.langchainSkillsFor(MEMORY_ID)).isInstanceOf(Skills.class);
        assertThat(service.skillsSystemMessage("other-memory")).isNull();
    }

    @Test
    void snapshotWithNoCandidatesIsEmptyAndWritesNothing() {
        when(definitionMapper.selectSnapshotCandidates(USER_ID)).thenReturn(List.of());

        SkillRuntimeService.PreparedSnapshot snapshot =
                service.snapshotForTopLevelRun(USER_ID, RUN_ID, MEMORY_ID);

        assertThat(snapshot.isEmpty()).isTrue();
        assertThat(snapshot.langchainSkills()).isNull();
        assertThat(service.skillsSystemMessage(MEMORY_ID)).isNull();
        verify(bindingMapper, never()).insert(any(AgentRunSkillBindingEntity.class));
    }

    @Test
    void snapshotFailsClosedWhenActiveReleaseMissing() {
        SkillDefinitionEntity definition = enabledSkill(11L, "demo-skill", 21L);
        when(definitionMapper.selectSnapshotCandidates(USER_ID)).thenReturn(List.of(definition));
        when(releaseMapper.selectBySkillAndId(11L, 21L)).thenReturn(null);

        assertThatThrownBy(() -> service.snapshotForTopLevelRun(USER_ID, RUN_ID, MEMORY_ID))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.ARTIFACT_UNAVAILABLE));
        verify(bindingMapper, never()).insert(any(AgentRunSkillBindingEntity.class));
    }

    @Test
    void snapshotFailsClosedWhenActiveReleaseRevoked() {
        SkillDefinitionEntity definition = enabledSkill(11L, "demo-skill", 21L);
        SkillReleaseEntity release = availableRelease(21L, 2);
        release.setStatus(SkillReleaseEntity.STATUS_REVOKED);
        when(definitionMapper.selectSnapshotCandidates(USER_ID)).thenReturn(List.of(definition));
        when(releaseMapper.selectBySkillAndId(11L, 21L)).thenReturn(release);

        assertThatThrownBy(() -> service.snapshotForTopLevelRun(USER_ID, RUN_ID, MEMORY_ID))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.RELEASE_REVOKED));
    }

    @Test
    void snapshotFailsClosedWhenArtifactCorrupt() {
        SkillDefinitionEntity definition = enabledSkill(11L, "demo-skill", 21L);
        SkillReleaseEntity release = availableRelease(21L, 2);
        when(definitionMapper.selectSnapshotCandidates(USER_ID)).thenReturn(List.of(definition));
        when(releaseMapper.selectBySkillAndId(11L, 21L)).thenReturn(release);
        when(artifactResolver.openVerified(any(ArtifactDescriptor.class)))
                .thenThrow(new SkillPlatformException(
                        SkillPlatformErrorKind.ARTIFACT_CORRUPT, "digest mismatch"));

        assertThatThrownBy(() -> service.snapshotForTopLevelRun(USER_ID, RUN_ID, MEMORY_ID))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.ARTIFACT_CORRUPT));
        verify(bindingMapper, never()).insert(any(AgentRunSkillBindingEntity.class));
    }

    @Test
    void snapshotLoadsEnabledSystemSkillFromConfig() {
        SkillPlatformProperties.SystemSkill systemSkill = new SkillPlatformProperties.SystemSkill();
        systemSkill.setKey("builtin-demo");
        systemSkill.setDisplayName("内置示例");
        systemSkill.setRevision("2026.08.1");
        systemSkill.setEnabled(true);
        systemSkill.getArtifact().setDigest("sha256:" + "b".repeat(64));
        systemSkill.getArtifact().setSize(100L);
        systemSkill.getArtifact().setObjectKey("v1/blobs/sha256/bb/bbb.skill.tar");
        properties.getSystemSkills().add(systemSkill);
        when(definitionMapper.selectSnapshotCandidates(USER_ID)).thenReturn(List.of());
        when(artifactResolver.openVerified(any(ArtifactDescriptor.class)))
                .thenAnswer(invocation -> bundleFor(invocation.getArgument(0)));

        SkillRuntimeService.PreparedSnapshot snapshot =
                service.snapshotForTopLevelRun(USER_ID, RUN_ID, MEMORY_ID);

        assertThat(snapshot.skills()).hasSize(1);
        assertThat(snapshot.skills().get(0).sourceType())
                .isEqualTo(AgentRunSkillBindingEntity.SOURCE_SYSTEM);
        assertThat(snapshot.skills().get(0).systemRevision()).isEqualTo("2026.08.1");
    }

    @Test
    void snapshotFailsClosedWhenSystemSkillConfigIncomplete() {
        SkillPlatformProperties.SystemSkill systemSkill = new SkillPlatformProperties.SystemSkill();
        systemSkill.setKey("builtin-demo");
        systemSkill.setEnabled(true);
        systemSkill.getArtifact().setDigest(null);
        properties.getSystemSkills().add(systemSkill);

        assertThatThrownBy(() -> service.snapshotForTopLevelRun(USER_ID, RUN_ID, MEMORY_ID))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.ARTIFACT_CORRUPT));
    }

    @Test
    void preparedSnapshotExpiresAfterTtl() {
        when(definitionMapper.selectSnapshotCandidates(USER_ID)).thenReturn(List.of());
        properties.getCache().setSnapshotTtl(java.time.Duration.ofSeconds(0));

        service.snapshotForTopLevelRun(USER_ID, RUN_ID, MEMORY_ID);
        assertThat(service.findPrepared(MEMORY_ID)).isNull();
    }
}
