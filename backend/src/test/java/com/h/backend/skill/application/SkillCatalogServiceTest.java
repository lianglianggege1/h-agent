package com.h.backend.skill.application;

import com.h.backend.skill.domain.ArtifactDescriptor;
import com.h.backend.skill.domain.SkillFileSet;
import com.h.backend.skill.domain.SkillPlatformErrorKind;
import com.h.backend.skill.domain.SkillPlatformException;
import com.h.backend.skill.domain.tar.DeterministicSkillTarBuilder;
import com.h.backend.skill.domain.tar.SkillBundleManifest;
import com.h.backend.skill.infrastructure.artifact.SkillArtifactPublisher;
import com.h.backend.skill.infrastructure.artifact.SkillArtifactResolver;
import com.h.backend.skill.infrastructure.artifact.VerifiedSkillBundle;
import com.h.backend.skill.infrastructure.config.SkillPlatformProperties;
import com.h.backend.skill.infrastructure.gitee.GiteeSkillRepository;
import com.h.backend.skill.infrastructure.persistence.entity.SkillDefinitionEntity;
import com.h.backend.skill.infrastructure.persistence.entity.SkillProposalEntity;
import com.h.backend.skill.infrastructure.persistence.entity.SkillReleaseEntity;
import com.h.backend.skill.infrastructure.persistence.mapper.AgentRunSkillBindingMapper;
import com.h.backend.skill.infrastructure.persistence.mapper.SkillDefinitionMapper;
import com.h.backend.skill.infrastructure.persistence.mapper.SkillOperationLogMapper;
import com.h.backend.skill.infrastructure.persistence.mapper.SkillProposalMapper;
import com.h.backend.skill.infrastructure.persistence.mapper.SkillPublicationOperationMapper;
import com.h.backend.skill.infrastructure.persistence.mapper.SkillReleaseMapper;
import com.h.backend.skill.infrastructure.validation.SkillContentValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SkillCatalogServiceTest {

    private static final long USER_ID = 7L;
    private static final long SKILL_ID = 11L;
    private static final long RELEASE_ID = 21L;
    private static final String HEAD = "abc123";

    private final SkillDefinitionMapper definitionMapper = mock(SkillDefinitionMapper.class);
    private final SkillProposalMapper proposalMapper = mock(SkillProposalMapper.class);
    private final SkillReleaseMapper releaseMapper = mock(SkillReleaseMapper.class);
    private final SkillPublicationOperationMapper publicationMapper = mock(SkillPublicationOperationMapper.class);
    private final SkillOperationLogMapper operationLogMapper = mock(SkillOperationLogMapper.class);
    private final GiteeSkillRepository gitee = mock(GiteeSkillRepository.class);
    private final SkillArtifactPublisher artifactPublisher = mock(SkillArtifactPublisher.class);
    private final SkillArtifactResolver artifactResolver = mock(SkillArtifactResolver.class);
    private final SkillPlatformProperties properties = new SkillPlatformProperties();

    private SkillCatalogService service;

    @BeforeEach
    void setUp() {
        SkillContentValidator validator = new SkillContentValidator(new SkillContentValidator.Quotas(
                properties.getValidation().getMaxUserSkills(),
                properties.getValidation().getMaxFileBytes(),
                properties.getValidation().getMaxTotalBytes(),
                properties.getValidation().getMaxFiles(),
                properties.getValidation().getMaxDepth()));
        DeterministicSkillTarBuilder tarBuilder = new DeterministicSkillTarBuilder(new ObjectMapper());
        TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Consumer.class).accept(null);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        properties.getSystemSkills().clear();
        service = new SkillCatalogService(
                definitionMapper, proposalMapper, releaseMapper, publicationMapper,
                operationLogMapper, gitee, validator, tarBuilder,
                artifactPublisher, artifactResolver, properties,
                new ObjectMapper(), transactionTemplate);
    }

    private static SkillDefinitionEntity ownedSkill(long revision) {
        SkillDefinitionEntity definition = new SkillDefinitionEntity();
        definition.setId(SKILL_ID);
        definition.setOwnerUserId(USER_ID);
        definition.setSkillKey("demo-skill");
        definition.setDisplayName("Demo");
        definition.setEnabled(false);
        definition.setRevision(revision);
        definition.setActiveReleaseId(RELEASE_ID);
        return definition;
    }

    private static SkillReleaseEntity release(int version, String status) {
        SkillReleaseEntity release = new SkillReleaseEntity();
        release.setId(RELEASE_ID);
        release.setSkillId(SKILL_ID);
        release.setVersionNumber(version);
        release.setStatus(status);
        release.setArtifactMediaType(ArtifactDescriptor.MEDIA_TYPE);
        release.setArtifactDigest("sha256:" + "a".repeat(64));
        release.setArtifactSize(100L);
        release.setArtifactStore(ArtifactDescriptor.USER_STORE);
        release.setArtifactObjectKey("v1/users/7/blobs/sha256/aa/aaa.skill.tar");
        return release;
    }

    @Test
    void createSkillRejectsReservedSystemKey() {
        SkillPlatformProperties.SystemSkill systemSkill = new SkillPlatformProperties.SystemSkill();
        systemSkill.setKey("builtin-demo");
        properties.getSystemSkills().add(systemSkill);

        assertThatThrownBy(() -> service.createSkill(USER_ID,
                new SkillCatalogService.CreateSkillCommand("builtin-demo", "Demo", null, null)))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.SKILL_INVALID));
    }

    @Test
    void createSkillRejectsWhenQuotaExceeded() {
        when(definitionMapper.countActiveOwned(USER_ID))
                .thenReturn((long) properties.getValidation().getMaxUserSkills());

        assertThatThrownBy(() -> service.createSkill(USER_ID,
                new SkillCatalogService.CreateSkillCommand("demo-skill", "Demo", null, null)))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.QUOTA_EXCEEDED));
    }

    @Test
    void createSkillRejectsDuplicateKey() {
        when(definitionMapper.countActiveOwned(USER_ID)).thenReturn(0L);
        when(definitionMapper.selectCount(any())).thenReturn(1L);

        assertThatThrownBy(() -> service.createSkill(USER_ID,
                new SkillCatalogService.CreateSkillCommand("demo-skill", "Demo", null, null)))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.OPERATION_CONFLICT));
    }

    @Test
    void getOwnSkillHidesForeignSkills() {
        when(definitionMapper.selectOwnedById(SKILL_ID, USER_ID)).thenReturn(null);
        when(proposalMapper.selectOpenBySkillId(SKILL_ID)).thenReturn(null);
        when(releaseMapper.selectBySkillId(SKILL_ID)).thenReturn(List.of());

        assertThatThrownBy(() -> service.getOwnSkill(USER_ID, SKILL_ID))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.SKILL_NOT_OWNED));
    }

    @Test
    void deleteSkillRejectsSkillWithReleases() {
        when(definitionMapper.selectOwnedById(SKILL_ID, USER_ID)).thenReturn(ownedSkill(1));
        when(releaseMapper.countBySkillId(SKILL_ID)).thenReturn(2L);

        assertThatThrownBy(() -> service.deleteSkill(USER_ID, SKILL_ID))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.OPERATION_CONFLICT));
    }

    @Test
    void activateReleaseRejectsStaleRevision() {
        when(definitionMapper.selectOwnedByIdForUpdate(SKILL_ID, USER_ID)).thenReturn(ownedSkill(5));
        when(releaseMapper.selectBySkillAndId(SKILL_ID, RELEASE_ID)).thenReturn(release(3, "AVAILABLE"));

        assertThatThrownBy(() -> service.activateRelease(USER_ID, SKILL_ID, RELEASE_ID, 4))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.ACTIVE_RELEASE_MISMATCH));
    }

    @Test
    void activateReleaseRejectsRevokedRelease() {
        when(definitionMapper.selectOwnedByIdForUpdate(SKILL_ID, USER_ID)).thenReturn(ownedSkill(1));
        when(releaseMapper.selectBySkillAndId(SKILL_ID, RELEASE_ID)).thenReturn(release(3, "REVOKED"));

        assertThatThrownBy(() -> service.activateRelease(USER_ID, SKILL_ID, RELEASE_ID, 1))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.RELEASE_REVOKED));
        verify(definitionMapper, never()).casActivateRelease(anyLong(), anyLong(), anyLong(), anyLong());
    }

    @Test
    void activateReleaseVerifiesArtifactBeforeActivation() {
        SkillDefinitionEntity definition = ownedSkill(1);
        definition.setActiveReleaseId(null);
        when(definitionMapper.selectOwnedByIdForUpdate(SKILL_ID, USER_ID)).thenReturn(definition);
        when(releaseMapper.selectBySkillAndId(SKILL_ID, RELEASE_ID)).thenReturn(release(3, "AVAILABLE"));
        when(definitionMapper.casActivateRelease(SKILL_ID, USER_ID, RELEASE_ID, 1)).thenReturn(1);
        when(definitionMapper.selectOwnedById(SKILL_ID, USER_ID)).thenReturn(definition);
        when(proposalMapper.selectOpenBySkillId(SKILL_ID)).thenReturn(null);
        when(releaseMapper.selectBySkillId(SKILL_ID)).thenReturn(List.of(release(3, "AVAILABLE")));

        service.activateRelease(USER_ID, SKILL_ID, RELEASE_ID, 1);

        verify(artifactResolver).verifyAvailable(any(ArtifactDescriptor.class));
        verify(definitionMapper).casActivateRelease(SKILL_ID, USER_ID, RELEASE_ID, 1);
    }

    @Test
    void setEnabledRequiresActiveRelease() {
        SkillDefinitionEntity definition = ownedSkill(1);
        definition.setActiveReleaseId(null);
        when(definitionMapper.selectOwnedByIdForUpdate(SKILL_ID, USER_ID)).thenReturn(definition);

        assertThatThrownBy(() -> service.setEnabled(USER_ID, SKILL_ID, true, 1))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.PROPOSAL_STATE_INVALID));
        verify(definitionMapper, never()).casSetEnabled(anyLong(), anyLong(), anyBoolean(), anyLong());
    }

    @Test
    void setEnabledRejectsRevokedActiveRelease() {
        when(definitionMapper.selectOwnedByIdForUpdate(SKILL_ID, USER_ID)).thenReturn(ownedSkill(1));
        when(releaseMapper.selectBySkillAndId(SKILL_ID, RELEASE_ID)).thenReturn(release(2, "REVOKED"));

        assertThatThrownBy(() -> service.setEnabled(USER_ID, SKILL_ID, true, 1))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.RELEASE_REVOKED));
    }

    @Test
    void setEnabledDoesNotRequireActiveReleaseWhenDisabling() {
        SkillDefinitionEntity definition = ownedSkill(1);
        definition.setActiveReleaseId(null);
        definition.setEnabled(true);
        when(definitionMapper.selectOwnedByIdForUpdate(SKILL_ID, USER_ID)).thenReturn(definition);
        when(definitionMapper.casSetEnabled(SKILL_ID, USER_ID, false, 1)).thenReturn(1);
        when(definitionMapper.selectOwnedById(SKILL_ID, USER_ID)).thenReturn(definition);
        when(proposalMapper.selectOpenBySkillId(SKILL_ID)).thenReturn(null);
        when(releaseMapper.selectBySkillId(SKILL_ID)).thenReturn(List.of());

        service.setEnabled(USER_ID, SKILL_ID, false, 1);

        verify(definitionMapper).casSetEnabled(SKILL_ID, USER_ID, false, 1);
        verify(artifactResolver, never()).verifyAvailable(any());
    }

    @Test
    void revokeReleaseRejectsEnabledActiveRelease() {
        SkillDefinitionEntity definition = ownedSkill(1);
        definition.setEnabled(true);
        when(definitionMapper.selectOwnedByIdForUpdate(SKILL_ID, USER_ID)).thenReturn(definition);
        when(releaseMapper.selectBySkillAndId(SKILL_ID, RELEASE_ID)).thenReturn(release(2, "AVAILABLE"));

        assertThatThrownBy(() -> service.revokeRelease(USER_ID, SKILL_ID, RELEASE_ID, "bad"))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.RELEASE_REVOKED));
        verify(releaseMapper, never()).revokeRelease(anyLong(), anyLong(), anyLong(), any());
    }

    @Test
    void publishReleaseRequiresIdempotencyKey() {
        when(definitionMapper.selectOwnedById(SKILL_ID, USER_ID)).thenReturn(ownedSkill(1));
        when(proposalMapper.selectOpenBySkillId(SKILL_ID)).thenReturn(openProposal());

        assertThatThrownBy(() -> service.publishRelease(USER_ID, SKILL_ID, HEAD, HEAD, "note", " "))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.OPERATION_CONFLICT));
    }

    @Test
    void publishReleaseRejectsStaleValidation() {
        when(definitionMapper.selectOwnedById(SKILL_ID, USER_ID)).thenReturn(ownedSkill(1));
        SkillProposalEntity proposal = openProposal();
        proposal.setValidationStatus(SkillProposalEntity.VALIDATION_UNVALIDATED);
        when(proposalMapper.selectOpenBySkillId(SKILL_ID)).thenReturn(proposal);

        assertThatThrownBy(() -> service.publishRelease(USER_ID, SKILL_ID, HEAD, HEAD, "note", "key-1"))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.VALIDATION_STALE));
    }

    @Test
    void publishReleaseRejectsHeadMismatch() {
        when(definitionMapper.selectOwnedById(SKILL_ID, USER_ID)).thenReturn(ownedSkill(1));
        when(proposalMapper.selectOpenBySkillId(SKILL_ID)).thenReturn(openProposal());

        assertThatThrownBy(() -> service.publishRelease(USER_ID, SKILL_ID, "other", HEAD, "note", "key-1"))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.PROPOSAL_HEAD_MISMATCH));
    }

    @Test
    void saveProposalRejectsHeadMismatch() {
        when(definitionMapper.selectOwnedById(SKILL_ID, USER_ID)).thenReturn(ownedSkill(1));
        when(proposalMapper.selectOpenBySkillId(SKILL_ID)).thenReturn(openProposal());

        assertThatThrownBy(() -> service.saveProposal(USER_ID, SKILL_ID, "other", List.of()))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.PROPOSAL_HEAD_MISMATCH));
    }

    @Test
    void discardProposalRejectsHeadMismatch() {
        when(definitionMapper.selectOwnedById(SKILL_ID, USER_ID)).thenReturn(ownedSkill(1));
        when(proposalMapper.selectOpenBySkillId(SKILL_ID)).thenReturn(openProposal());

        assertThatThrownBy(() -> service.discardProposal(USER_ID, SKILL_ID, "other"))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.PROPOSAL_HEAD_MISMATCH));
        verify(gitee, never()).deleteBranch(any());
    }

    @Test
    void archiveSkillUsesExpectedRevisionCas() {
        when(definitionMapper.selectOwnedByIdForUpdate(SKILL_ID, USER_ID)).thenReturn(ownedSkill(1));
        when(definitionMapper.casArchive(SKILL_ID, USER_ID, 1)).thenReturn(1);
        SkillDefinitionEntity archived = ownedSkill(2);
        when(definitionMapper.selectOwnedById(SKILL_ID, USER_ID)).thenReturn(archived);
        when(proposalMapper.selectOpenBySkillId(SKILL_ID)).thenReturn(null);
        when(releaseMapper.selectBySkillId(SKILL_ID)).thenReturn(List.of());

        service.archiveSkill(USER_ID, SKILL_ID, 1);

        verify(definitionMapper).casArchive(SKILL_ID, USER_ID, 1);
    }

    @Test
    void restoreSkillRejectsStaleRevision() {
        when(definitionMapper.selectOwnedByIdForUpdate(SKILL_ID, USER_ID)).thenReturn(ownedSkill(9));
        when(definitionMapper.casRestore(SKILL_ID, USER_ID, 9)).thenReturn(0);

        assertThatThrownBy(() -> service.restoreSkill(USER_ID, SKILL_ID, 8))
                .isInstanceOfSatisfying(SkillPlatformException.class,
                        ex -> assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.ACTIVE_RELEASE_MISMATCH));
    }

    @Test
    void validateProposalReturnsAndRecordsResultForMatchingHead() {
        SkillDefinitionEntity definition = ownedSkill(1);
        definition.setSkillKey("demo-skill");
        when(definitionMapper.selectOwnedById(SKILL_ID, USER_ID)).thenReturn(definition);
        SkillProposalEntity proposal = openProposal();
        when(proposalMapper.selectOpenBySkillId(SKILL_ID)).thenReturn(proposal);
        when(gitee.listFilesUnder("users/7/skills/demo-skill", "refs/heads/p/1")).thenReturn(List.of());
        when(proposalMapper.recordValidation(eq(proposal.getId()), eq(HEAD), any(), any())).thenReturn(1);

        var result = service.validateProposal(USER_ID, SKILL_ID, HEAD);

        assertThat(result.validatedHeadSha()).isEqualTo(HEAD);
        assertThat(SkillCatalogService.ValidationOutcomeView.from(result).headCommitSha()).isEqualTo(HEAD);
        verify(proposalMapper).recordValidation(eq(proposal.getId()), eq(HEAD),
                eq(SkillProposalEntity.VALIDATION_INVALID), any());
    }

    private SkillProposalEntity openProposal() {
        SkillProposalEntity proposal = new SkillProposalEntity();
        proposal.setId(31L);
        proposal.setSkillId(SKILL_ID);
        proposal.setBranchName("refs/heads/p/1");
        proposal.setHeadCommitSha(HEAD);
        proposal.setValidationStatus(SkillProposalEntity.VALIDATION_VALID);
        proposal.setValidatedHeadSha(HEAD);
        proposal.setStatus(SkillProposalEntity.STATUS_OPEN);
        return proposal;
    }
}
