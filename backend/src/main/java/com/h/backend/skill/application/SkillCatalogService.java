package com.h.backend.skill.application;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.h.backend.skill.domain.ArtifactDescriptor;
import com.h.backend.skill.domain.SkillFileSet;
import com.h.backend.skill.domain.SkillPlatformErrorKind;
import com.h.backend.skill.domain.SkillPlatformException;
import com.h.backend.skill.domain.SkillValidationResult;
import com.h.backend.skill.domain.tar.DeterministicSkillTarBuilder;
import com.h.backend.skill.domain.tar.SkillBundleManifest;
import com.h.backend.skill.infrastructure.artifact.SkillArtifactPublisher;
import com.h.backend.skill.infrastructure.artifact.SkillArtifactResolver;
import com.h.backend.skill.infrastructure.config.SkillPlatformProperties;
import com.h.backend.skill.infrastructure.gitee.GiteeSkillRepository;
import com.h.backend.skill.infrastructure.persistence.entity.SkillDefinitionEntity;
import com.h.backend.skill.infrastructure.persistence.entity.SkillOperationLogEntity;
import com.h.backend.skill.infrastructure.persistence.entity.SkillProposalEntity;
import com.h.backend.skill.infrastructure.persistence.entity.SkillPublicationOperationEntity;
import com.h.backend.skill.infrastructure.persistence.entity.SkillReleaseEntity;
import com.h.backend.skill.infrastructure.persistence.mapper.SkillDefinitionMapper;
import com.h.backend.skill.infrastructure.persistence.mapper.SkillOperationLogMapper;
import com.h.backend.skill.infrastructure.persistence.mapper.SkillProposalMapper;
import com.h.backend.skill.infrastructure.persistence.mapper.SkillPublicationOperationMapper;
import com.h.backend.skill.infrastructure.persistence.mapper.SkillReleaseMapper;
import com.h.backend.skill.infrastructure.validation.SkillContentValidator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * SkillCatalog 深模块（设计 §8/§11）：用户 Skill 的 Proposal 编辑、发布、
 * 生效、启停、撤销、归档与查询。
 *
 * <p>Controller 与 Runtime 不直接接触 Gitee client、branch、tag 或数据库表。
 * 发布流程：校验证据 -> 重读 proposal head 内容并复核 -> 构建确定性 bundle ->
 * MinIO 制品（读回验证）-> squash 合并到 master -> Release tag（重读验证）->
 * PostgreSQL 登记不可变 Release -> 删除 Proposal。
 *
 * <p>发布、设为生效、启用是三个独立动作；本类不提供任何组合命令（设计 §7.1）。
 */
@Slf4j
@Service
public class SkillCatalogService {

    private final SkillDefinitionMapper definitionMapper;
    private final SkillProposalMapper proposalMapper;
    private final SkillReleaseMapper releaseMapper;
    private final SkillPublicationOperationMapper publicationMapper;
    private final SkillOperationLogMapper operationLogMapper;
    private final GiteeSkillRepository gitee;
    private final SkillContentValidator validator;
    private final DeterministicSkillTarBuilder tarBuilder;
    private final SkillArtifactPublisher artifactPublisher;
    private final SkillArtifactResolver artifactResolver;
    private final SkillPlatformProperties properties;
    private final ObjectMapper objectMapper;
    private final TransactionTemplate transactionTemplate;

    public SkillCatalogService(
            SkillDefinitionMapper definitionMapper,
            SkillProposalMapper proposalMapper,
            SkillReleaseMapper releaseMapper,
            SkillPublicationOperationMapper publicationMapper,
            SkillOperationLogMapper operationLogMapper,
            GiteeSkillRepository gitee,
            SkillContentValidator validator,
            DeterministicSkillTarBuilder tarBuilder,
            SkillArtifactPublisher artifactPublisher,
            SkillArtifactResolver artifactResolver,
            SkillPlatformProperties properties,
            ObjectMapper objectMapper,
            TransactionTemplate transactionTemplate
    ) {
        this.definitionMapper = definitionMapper;
        this.proposalMapper = proposalMapper;
        this.releaseMapper = releaseMapper;
        this.publicationMapper = publicationMapper;
        this.operationLogMapper = operationLogMapper;
        this.gitee = gitee;
        this.validator = validator;
        this.tarBuilder = tarBuilder;
        this.artifactPublisher = artifactPublisher;
        this.artifactResolver = artifactResolver;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.transactionTemplate = transactionTemplate;
    }

    // ==================================================================
    // 视图 DTO
    // ==================================================================

    public record CreateSkillCommand(String skillKey, String displayName, String description, String skillMd) {
    }

    public record ProposalFileView(String path, long size, String contentBase64) {
    }

    public record ProposalView(
            Long proposalId, String headCommitSha, long revision, String validationStatus,
            List<String> validationErrors, List<String> validationWarnings,
            List<ProposalFileView> files, LocalDateTime updatedAt
    ) {
    }

    public record SkillSummaryView(
            Long id, String skillKey, String displayName, String description, String sourceType,
            boolean enabled, boolean archived, long revision,
            Long activeReleaseId, Integer activeVersion, boolean hasOpenProposal,
            String openProposalValidationStatus, LocalDateTime lastPublishedAt, LocalDateTime updatedAt
    ) {
    }

    public record ReleaseSummaryView(
            Long id, int versionNumber, String digest, long size,
            String releaseNote, String status, String commitSha, LocalDateTime createdAt,
            boolean revoked, String revokeReason
    ) {
    }

    public record ReleaseDetailView(
            ReleaseSummaryView summary, String builderVersion, String validationPolicyVersion,
            String securityPolicyVersion, List<ProposalFileView> files,
            List<ManifestEntryView> manifest, List<String> validationWarnings, boolean isActive
    ) {
    }

    public record ManifestEntryView(String path, long size, String sha256) {
    }

    public record FileDiffView(String path, String change) {
    }

    public record ReleaseCompareView(
            Long fromReleaseId, int fromVersion, Long toReleaseId, int toVersion,
            List<FileDiffView> changes, long filesAdded, long filesModified, long filesRemoved
    ) {
    }

    public record SaveProposalChange(String path, String contentBase64) {
    }

    public record ValidationOutcomeView(
            boolean valid, List<String> errors, List<String> warnings, String headCommitSha
    ) {

        public static ValidationOutcomeView from(SkillValidationResult result) {
            return new ValidationOutcomeView(
                    result.valid(), result.errors(), result.warnings(), result.validatedHeadSha());
        }
    }

    // ==================================================================
    // 创建 / 查询
    // ==================================================================

    public SkillSummaryView createSkill(long userId, CreateSkillCommand command) {
        Objects.requireNonNull(command, "command must not be null");
        String skillKey = normalizeKey(command.skillKey());
        String displayName = requireText(command.displayName(), "displayName", 255);
        String description = normalizeText(command.description(), 2000);

        Set<String> reserved = reservedKeys();
        if (reserved.contains(skillKey)) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_INVALID, "skill_key 与系统内置 Skill 冲突: " + skillKey);
        }
        if (countSkillByKey(userId, skillKey) > 0) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.OPERATION_CONFLICT, "相同 key 的 Skill 已存在");
        }
        SkillPlatformProperties.Validation quotas = properties.getValidation();
        if (definitionMapper.countActiveOwned(userId) >= quotas.getMaxUserSkills()) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.QUOTA_EXCEEDED,
                    "未归档 Skill 数已达上限 " + quotas.getMaxUserSkills());
        }

        SkillDefinitionEntity definition = new SkillDefinitionEntity();
        definition.setOwnerUserId(userId);
        definition.setSkillKey(skillKey);
        definition.setDisplayName(displayName);
        definition.setDescription(description);
        definition.setSourceType(SkillDefinitionEntity.SOURCE_TYPE_USER);
        definition.setEnabled(false);
        definition.setRevision(1L);
        definitionMapper.insert(definition);

        String directory = gitDirectory(userId, skillKey);
        String branch = "proposal/" + userId + "/" + definition.getId() + "/"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        gitee.createBranch(branch, properties.getRepository().getBranch());
        Map<String, byte[]> initial = new LinkedHashMap<>();
        String skillMd = (command.skillMd() == null || command.skillMd().isBlank())
                ? defaultSkillMd(skillKey, displayName)
                : command.skillMd();
        initial.put("SKILL.md", skillMd.getBytes(StandardCharsets.UTF_8));
        initial.put("skill.yaml", defaultSkillYaml(skillKey, displayName).getBytes(StandardCharsets.UTF_8));
        String head = pushFiles(directory, branch, initial);

        insertProposal(definition.getId(), branch, head, null, userId);

        logOperation(userId, definition.getId(), null, "CREATE", null, Map.of(
                "skillKey", skillKey, "displayName", displayName));
        return getOwnSkill(userId, definition.getId());
    }

    public List<SkillSummaryView> listOwnSkills(long userId) {
        return definitionMapper.selectOwnedActive(userId).stream()
                .map(definition -> {
                    SkillProposalEntity proposal = proposalMapper.selectOpenBySkillId(definition.getId());
                    List<SkillReleaseEntity> releases = releaseMapper.selectBySkillId(definition.getId());
                    SkillReleaseEntity activeRelease = definition.getActiveReleaseId() == null
                            ? null
                            : findRelease(releases, definition.getActiveReleaseId());
                    LocalDateTime lastPublishedAt = releases.isEmpty() ? null : releases.get(0).getCreatedAt();
                    return assembleSummary(definition, proposal, activeRelease, lastPublishedAt);
                })
                .toList();
    }

    public SkillSummaryView getOwnSkill(long userId, long skillId) {
        SkillDefinitionEntity definition = requireOwned(userId, skillId);
        SkillProposalEntity proposal = proposalMapper.selectOpenBySkillId(skillId);
        List<SkillReleaseEntity> releases = releaseMapper.selectBySkillId(skillId);
        SkillReleaseEntity activeRelease = definition.getActiveReleaseId() == null
                ? null
                : findRelease(releases, definition.getActiveReleaseId());
        LocalDateTime lastPublishedAt = releases.isEmpty() ? null : releases.get(0).getCreatedAt();
        return assembleSummary(definition, proposal, activeRelease, lastPublishedAt);
    }

    public void deleteSkill(long userId, long skillId) {
        SkillDefinitionEntity definition = requireOwned(userId, skillId);
        if (releaseMapper.countBySkillId(skillId) > 0) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.OPERATION_CONFLICT,
                    "已发布的 Skill 只能归档，不能删除");
        }
        SkillProposalEntity proposal = proposalMapper.selectOpenBySkillId(skillId);
        if (proposal != null) {
            safeDeleteBranch(proposal.getBranchName());
            proposalMapper.deleteById(proposal.getId());
        }
        definitionMapper.deleteById(skillId);
        logOperation(userId, skillId, null, "DELETE", null, Map.of("skillKey", definition.getSkillKey()));
    }

    // ==================================================================
    // Proposal：读取 / 升级创建 / 保存 / 校验 / 放弃
    // ==================================================================

    public ProposalView getProposal(long userId, long skillId) {
        SkillDefinitionEntity definition = requireOwned(userId, skillId);
        SkillProposalEntity proposal = requireOpenProposal(skillId);
        Map<String, byte[]> contents = readBranchFiles(gitDirectory(userId, definition.getSkillKey()),
                proposal.getBranchName());
        return toProposalView(proposal, contents);
    }

    /**
     * 基于指定 Release（缺省为 Active 或最新版）创建升级 Proposal（设计 §7.2）；
     * 一个 Skill 同时最多一个 OPEN Proposal。
     */
    public ProposalView createProposalFromRelease(long userId, long skillId, Long baseReleaseId) {
        SkillDefinitionEntity definition = requireOwned(userId, skillId);
        if (proposalMapper.selectOpenBySkillId(skillId) != null) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.PROPOSAL_STATE_INVALID,
                    "已存在进行中的草稿；请先发布或放弃当前草稿");
        }
        List<SkillReleaseEntity> releases = releaseMapper.selectBySkillId(skillId);
        SkillReleaseEntity base = null;
        if (baseReleaseId != null) {
            base = findRelease(releases, baseReleaseId);
            if (base == null) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_NOT_OWNED, "基线 Release 不存在");
            }
        } else if (definition.getActiveReleaseId() != null) {
            base = findRelease(releases, definition.getActiveReleaseId());
        } else if (!releases.isEmpty()) {
            base = releases.get(0);
        }

        String directory = gitDirectory(userId, definition.getSkillKey());
        String branch = "proposal/" + userId + "/" + skillId + "/"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        gitee.createBranch(branch, properties.getRepository().getBranch());

        Map<String, byte[]> seed = base == null
                ? Map.of("SKILL.md", defaultSkillMd(definition.getSkillKey(), definition.getDisplayName())
                        .getBytes(StandardCharsets.UTF_8))
                : readTagFiles(directory, base.getTagName());
        String head = pushFiles(directory, branch, seed);

        insertProposal(skillId, branch, head, base == null ? null : base.getId(), userId);
        SkillProposalEntity proposal = requireOpenProposal(skillId);
        return toProposalView(proposal, seed);
    }

    public ProposalView saveProposal(long userId, long skillId, String expectedHead, List<SaveProposalChange> changes) {
        SkillDefinitionEntity definition = requireOwned(userId, skillId);
        SkillProposalEntity proposal = requireOpenProposal(skillId);
        if (!Objects.equals(proposal.getHeadCommitSha(), expectedHead)) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.PROPOSAL_HEAD_MISMATCH, "草稿已被其他操作修改");
        }
        if (changes == null || changes.isEmpty()) {
            return toProposalView(proposal, readBranchFiles(gitDirectory(userId, definition.getSkillKey()),
                    proposal.getBranchName()));
        }

        String directory = gitDirectory(userId, definition.getSkillKey());
        String branch = proposal.getBranchName();
        Map<String, byte[]> current = readBranchFiles(directory, branch);
        Map<String, byte[]> next = new LinkedHashMap<>(current);
        for (SaveProposalChange change : changes) {
            String path = requireRelativePath(change.path());
            if (change.contentBase64() == null || change.contentBase64().isEmpty()) {
                next.remove(path);
            } else {
                next.put(path, Base64.getMimeDecoder().decode(change.contentBase64()));
            }
        }

        // 推送远端前的最低安全校验：路径与高置信度凭据（设计 §9.3）
        List<String> blockers = validator.remoteWriteBlockers(SkillFileSet.of(next));
        if (!blockers.isEmpty()) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_INVALID, String.join("; ", blockers));
        }

        String newHead = proposal.getHeadCommitSha();
        for (SaveProposalChange change : changes) {
            String path = requireRelativePath(change.path());
            String fullPath = directory + "/" + path;
            if (change.contentBase64() == null || change.contentBase64().isEmpty()) {
                String sha = gitee.readFileSha(fullPath, branch);
                if (sha != null) {
                    gitee.deleteFile(fullPath, branch, sha, "chore(skill): remove " + path);
                }
            } else {
                newHead = gitee.putFile(fullPath, branch, change.contentBase64(), "chore(skill): update " + path);
            }
        }

        int updated = proposalMapper.advanceHead(proposal.getId(), expectedHead, newHead, userId);
        if (updated == 0) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.PROPOSAL_HEAD_MISMATCH, "草稿已被其他操作修改");
        }
        SkillProposalEntity refreshed = proposalMapper.selectById(proposal.getId());
        return toProposalView(refreshed, readBranchFiles(directory, branch));
    }

    public SkillValidationResult validateProposal(long userId, long skillId, String expectedHead) {
        SkillDefinitionEntity definition = requireOwned(userId, skillId);
        SkillProposalEntity proposal = requireOpenProposal(skillId);
        if (!Objects.equals(proposal.getHeadCommitSha(), expectedHead)) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.PROPOSAL_HEAD_MISMATCH, "草稿已被其他操作修改");
        }
        Map<String, byte[]> contents = readBranchFiles(
                gitDirectory(userId, definition.getSkillKey()), proposal.getBranchName());
        SkillValidationResult contentValidation = validator.validate(
                SkillFileSet.of(contents), definition.getSkillKey(), reservedKeys());
        SkillValidationResult result = contentValidation.valid()
                ? SkillValidationResult.ok(contentValidation.warnings(), expectedHead)
                : SkillValidationResult.invalid(
                        contentValidation.errors(), contentValidation.warnings(), expectedHead);
        String json = toJson(Map.of(
                "errors", result.errors(),
                "warnings", result.warnings()));
        proposalMapper.recordValidation(proposal.getId(), expectedHead,
                result.valid() ? SkillProposalEntity.VALIDATION_VALID : SkillProposalEntity.VALIDATION_INVALID, json);
        return result;
    }

    public void discardProposal(long userId, long skillId, String expectedHead) {
        requireOwned(userId, skillId);
        SkillProposalEntity proposal = requireOpenProposal(skillId);
        if (!Objects.equals(proposal.getHeadCommitSha(), expectedHead)) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.PROPOSAL_HEAD_MISMATCH, "草稿已被其他操作修改");
        }
        safeDeleteBranch(proposal.getBranchName());
        proposalMapper.deleteById(proposal.getId());
        logOperation(userId, skillId, null, "DISCARD_PROPOSAL", null, Map.of("head", expectedHead));
    }

    // ==================================================================
    // 发布
    // ==================================================================

    public ReleaseSummaryView publishRelease(
            long userId, long skillId, String expectedHead, String validatedHead,
            String releaseNote, String idempotencyKey
    ) {
        SkillDefinitionEntity definition = requireOwned(userId, skillId);
        String note = requireReleaseNote(releaseNote);
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.OPERATION_CONFLICT, "缺少 Idempotency-Key");
        }
        SkillProposalEntity proposal = requireOpenProposal(skillId);
        if (!Objects.equals(proposal.getHeadCommitSha(), expectedHead)) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.PROPOSAL_HEAD_MISMATCH, "草稿已被其他操作修改");
        }
        if (!Objects.equals(expectedHead, validatedHead)
                || !SkillProposalEntity.VALIDATION_VALID.equals(proposal.getValidationStatus())
                || !Objects.equals(validatedHead, proposal.getValidatedHeadSha())) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.VALIDATION_STALE, "当前内容缺少有效校验，请先重新校验");
        }

        SkillPublicationOperationEntity operation = findOrCreatePublicationOperation(
                idempotencyKey, skillId, proposal.getId(), expectedHead);
        if (SkillPublicationOperationEntity.STATE_COMPLETED.equals(operation.getState())
                && operation.getReservedReleaseId() != null) {
            SkillReleaseEntity existing = releaseMapper.selectById(operation.getReservedReleaseId());
            if (existing != null) {
                return toReleaseSummary(existing);
            }
        }

        int marked = proposalMapper.markPublishing(proposal.getId(), expectedHead);
        if (marked == 0) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.PROPOSAL_HEAD_MISMATCH, "草稿已被其他操作修改");
        }
        try {
            return executePublication(userId, definition, proposal, note, operation);
        } catch (RuntimeException ex) {
            proposalMapper.reopen(proposal.getId());
            markOperationFailed(operation.getId(), errorCodeOf(ex));
            throw ex;
        }
    }

    private ReleaseSummaryView executePublication(
            long userId, SkillDefinitionEntity definition, SkillProposalEntity proposal,
            String note, SkillPublicationOperationEntity operation
    ) {
        long skillId = definition.getId();
        String directory = gitDirectory(userId, definition.getSkillKey());

        // 1. 重读 proposal head 内容并复核校验（设计 §12 步骤 5）
        Map<String, byte[]> contents = readBranchFiles(directory, proposal.getBranchName());
        SkillValidationResult revalidated = validator.validate(
                SkillFileSet.of(contents), definition.getSkillKey(), reservedKeys());
        if (!revalidated.valid()) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_INVALID,
                    "发布校验失败: " + String.join("; ", revalidated.errors()));
        }
        SkillFileSet fileSet = SkillFileSet.of(contents);

        // 2. 构建确定性 bundle 并发布不可变制品（读回验证）
        updateOperation(operation.getId(), SkillPublicationOperationEntity.STATE_PREPARED, null, null, null);
        byte[] bundle = tarBuilder.build(fileSet);
        ArtifactDescriptor descriptor = artifactPublisher.storeVerifiedUserBundle(userId, bundle);
        updateOperation(operation.getId(), SkillPublicationOperationEntity.STATE_ARTIFACT_STORED_VERIFIED,
                null, toJson(descriptor), null);

        // 3. squash 合并到 master 形成干净 publication commit
        long prNumber = gitee.createPullRequest(proposal.getBranchName(),
                properties.getRepository().getBranch(), "publish skill " + definition.getSkillKey());
        String mergeSha = gitee.mergePullRequest(prNumber,
                "publish " + definition.getSkillKey(), "release: " + note);
        updateOperation(operation.getId(), SkillPublicationOperationEntity.STATE_MASTER_UPDATED,
                toJson(Map.of("mergeCommitSha", mergeSha, "prNumber", prNumber)), null, null);

        // 4. 创建并验证 Release tag
        int version = releaseMapper.selectMaxVersion(skillId) + 1;
        String tagName = releaseTagName(userId, skillId, version);
        gitee.createTag(tagName, mergeSha, "Skill release v" + version + ": " + note);
        String verifiedCommit = gitee.verifyTagCommit(tagName);
        if (verifiedCommit == null || !verifiedCommit.equals(mergeSha)) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SOURCE_DRIFTED, "Release tag 验证失败");
        }
        updateOperation(operation.getId(), SkillPublicationOperationEntity.STATE_TAG_VERIFIED,
                toJson(Map.of("tagName", tagName, "commitSha", verifiedCommit)), null, null);

        // 5. PostgreSQL 登记不可变 Release（不写 Activation/Enable，设计 §12 步骤 10）
        SkillReleaseEntity release = transactionTemplate.execute(status -> {
            SkillDefinitionEntity locked = definitionMapper.selectOwnedByIdForUpdate(skillId, userId);
            if (locked == null) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_NOT_OWNED);
            }
            SkillReleaseEntity entity = new SkillReleaseEntity();
            entity.setSkillId(skillId);
            entity.setVersionNumber(version);
            entity.setTagName(tagName);
            entity.setCommitSha(mergeSha);
            entity.setArtifactStore(descriptor.store());
            entity.setArtifactObjectKey(descriptor.objectKey());
            entity.setArtifactObjectVersionId(descriptor.objectVersionId());
            entity.setArtifactMediaType(descriptor.mediaType());
            entity.setArtifactDigest(descriptor.digest());
            entity.setArtifactSize(descriptor.size());
            entity.setBuilderVersion(DeterministicSkillTarBuilder.BUILDER_VERSION);
            entity.setValidationPolicyVersion(SkillContentValidator.VALIDATION_POLICY_VERSION);
            entity.setSecurityPolicyVersion(SkillContentValidator.SECURITY_POLICY_VERSION);
            entity.setReleaseNote(note);
            entity.setManifestJson(toJson(buildManifest(fileSet)));
            entity.setValidationSummaryJson(toJson(Map.of("warnings", revalidated.warnings())));
            entity.setStatus(SkillReleaseEntity.STATUS_AVAILABLE);
            entity.setCreatedBy(userId);
            releaseMapper.insertRelease(entity);

            // 显示名称与说明可随新 Release 改变（设计不变量 4）
            String displayName = readSkillYamlField(contents, "displayName");
            if (displayName != null && !displayName.isBlank()) {
                SkillDefinitionEntity update = new SkillDefinitionEntity();
                update.setId(skillId);
                update.setDisplayName(displayName);
                definitionMapper.updateById(update);
            }
            updateOperation(operation.getId(), SkillPublicationOperationEntity.STATE_RELEASE_INDEXED,
                    null, null, null);
            publicationMapper.updateReservedRelease(operation.getId(), entity.getId(), version);
            return entity;
        });

        // 6. 删除 Proposal 分支与记录（失败只进入后台清理，不影响已登记 Release）
        safeDeleteBranch(proposal.getBranchName());
        proposalMapper.deleteById(proposal.getId());
        updateOperation(operation.getId(), SkillPublicationOperationEntity.STATE_COMPLETED, null, null, null);

        logOperation(userId, skillId, release.getId(), "PUBLISH", null, Map.of(
                "version", version, "digest", descriptor.digest(), "tagName", tagName));
        log.info("Skill 发布完成 userId={} skillId={} v{} digest={}",
                userId, skillId, version, descriptor.digest());
        return toReleaseSummary(release);
    }

    // ==================================================================
    // Release：查询 / 比较 / 生效 / 撤销
    // ==================================================================

    public List<ReleaseSummaryView> listReleases(long userId, long skillId) {
        requireOwned(userId, skillId);
        return releaseMapper.selectBySkillId(skillId).stream()
                .map(this::toReleaseSummary)
                .toList();
    }

    public ReleaseDetailView getRelease(long userId, long skillId, long releaseId) {
        SkillDefinitionEntity definition = requireOwned(userId, skillId);
        SkillReleaseEntity release = requireRelease(skillId, releaseId);
        Map<String, byte[]> contents = readTagFiles(
                gitDirectory(userId, definition.getSkillKey()), release.getTagName());
        List<ProposalFileView> files = contents.entrySet().stream()
                .map(entry -> new ProposalFileView(entry.getKey(), entry.getValue().length,
                        Base64.getEncoder().encodeToString(entry.getValue())))
                .toList();
        return new ReleaseDetailView(
                toReleaseSummary(release),
                release.getBuilderVersion(),
                release.getValidationPolicyVersion(),
                release.getSecurityPolicyVersion(),
                files,
                readManifestEntries(release),
                readValidationWarnings(release),
                Objects.equals(definition.getActiveReleaseId(), releaseId));
    }

    public ReleaseCompareView compareReleases(long userId, long skillId, long fromId, long toId) {
        SkillDefinitionEntity definition = requireOwned(userId, skillId);
        SkillReleaseEntity from = requireRelease(skillId, fromId);
        SkillReleaseEntity to = requireRelease(skillId, toId);
        String directory = gitDirectory(userId, definition.getSkillKey());
        Map<String, byte[]> fromFiles = readTagFiles(directory, from.getTagName());
        Map<String, byte[]> toFiles = readTagFiles(directory, to.getTagName());

        Set<String> allPaths = new java.util.TreeSet<>(fromFiles.keySet());
        allPaths.addAll(toFiles.keySet());
        List<FileDiffView> changes = new ArrayList<>();
        long added = 0;
        long modified = 0;
        long removed = 0;
        for (String path : allPaths) {
            byte[] fromContent = fromFiles.get(path);
            byte[] toContent = toFiles.get(path);
            if (fromContent == null && toContent != null) {
                changes.add(new FileDiffView(path, "added"));
                added++;
            } else if (fromContent != null && toContent == null) {
                changes.add(new FileDiffView(path, "removed"));
                removed++;
            } else if (fromContent != null && !java.util.Arrays.equals(fromContent, toContent)) {
                changes.add(new FileDiffView(path, "modified"));
                modified++;
            }
        }
        return new ReleaseCompareView(fromId, from.getVersionNumber(), toId, to.getVersionNumber(),
                changes, added, modified, removed);
    }

    public SkillSummaryView activateRelease(long userId, long skillId, long releaseId, long expectedRevision) {
        transactionTemplate.executeWithoutResult(status -> {
            SkillDefinitionEntity definition = definitionMapper.selectOwnedByIdForUpdate(skillId, userId);
            if (definition == null) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_NOT_OWNED);
            }
            if (definition.getRevision() != expectedRevision) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.ACTIVE_RELEASE_MISMATCH, "Skill 状态已变化，请刷新后重试");
            }
            SkillReleaseEntity target = requireRelease(skillId, releaseId);
            if (SkillReleaseEntity.STATUS_REVOKED.equals(target.getStatus())) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.RELEASE_REVOKED);
            }
            // 生效前验证 Artifact 当前可取得（设计不变量 15）
            artifactResolver.verifyAvailable(toDescriptor(target));
            int updated = definitionMapper.casActivateRelease(skillId, userId, releaseId, expectedRevision);
            if (updated == 0) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.ACTIVE_RELEASE_MISMATCH, "Skill 状态已变化，请刷新后重试");
            }
            boolean rollback = definition.getActiveReleaseId() != null
                    && target.getVersionNumber() < currentVersionOf(skillId, definition.getActiveReleaseId());
            logOperation(userId, skillId, releaseId, rollback ? "ROLLBACK" : "ACTIVATE",
                    Map.of("activeReleaseId", String.valueOf(definition.getActiveReleaseId())),
                    Map.of("activeReleaseId", String.valueOf(releaseId)));
        });
        return getOwnSkill(userId, skillId);
    }

    public SkillSummaryView setEnabled(long userId, long skillId, boolean enabled, long expectedRevision) {
        transactionTemplate.executeWithoutResult(status -> {
            SkillDefinitionEntity definition = definitionMapper.selectOwnedByIdForUpdate(skillId, userId);
            if (definition == null) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_NOT_OWNED);
            }
            if (definition.getRevision() != expectedRevision) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.ACTIVE_RELEASE_MISMATCH, "Skill 状态已变化，请刷新后重试");
            }
            if (enabled) {
                if (definition.getActiveReleaseId() == null) {
                    throw SkillPlatformException.of(SkillPlatformErrorKind.PROPOSAL_STATE_INVALID,
                            "启用前必须先设为生效版本");
                }
                SkillReleaseEntity active = requireRelease(skillId, definition.getActiveReleaseId());
                if (SkillReleaseEntity.STATUS_REVOKED.equals(active.getStatus())) {
                    throw SkillPlatformException.of(SkillPlatformErrorKind.RELEASE_REVOKED,
                            "生效版本已撤销，请先切换 Active Release");
                }
                artifactResolver.verifyAvailable(toDescriptor(active));
            }
            int updated = definitionMapper.casSetEnabled(skillId, userId, enabled, expectedRevision);
            if (updated == 0) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.ACTIVE_RELEASE_MISMATCH, "Skill 状态已变化，请刷新后重试");
            }
            logOperation(userId, skillId, null, enabled ? "ENABLE" : "DISABLE", null, Map.of("enabled", enabled));
        });
        return getOwnSkill(userId, skillId);
    }

    public SkillSummaryView revokeRelease(long userId, long skillId, long releaseId, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            SkillDefinitionEntity definition = definitionMapper.selectOwnedByIdForUpdate(skillId, userId);
            if (definition == null) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_NOT_OWNED);
            }
            requireRelease(skillId, releaseId);
            boolean isActive = Objects.equals(definition.getActiveReleaseId(), releaseId);
            if (isActive && Boolean.TRUE.equals(definition.getEnabled())) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.RELEASE_REVOKED,
                        "启用状态的生效版本不能撤销；请先切换 Active Release 或先停用");
            }
            int updated = releaseMapper.revokeRelease(skillId, releaseId, userId, normalizeText(reason, 2000));
            if (updated == 0) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.OPERATION_CONFLICT,
                        "该 Release 已撤销或状态已变化");
            }
            if (isActive) {
                definitionMapper.casActivateRelease(skillId, userId, null, definition.getRevision());
            }
            logOperation(userId, skillId, releaseId, "REVOKE",
                    Map.of("status", "AVAILABLE"), Map.of("reason", normalizeText(reason, 2000) == null ? "" : reason));
        });
        return getOwnSkill(userId, skillId);
    }

    public SkillSummaryView archiveSkill(long userId, long skillId, long expectedRevision) {
        transactionTemplate.executeWithoutResult(status -> {
            SkillDefinitionEntity definition = definitionMapper.selectOwnedByIdForUpdate(skillId, userId);
            if (definition == null) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_NOT_OWNED);
            }
            int updated = definitionMapper.casArchive(skillId, userId, expectedRevision);
            if (updated == 0) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.ACTIVE_RELEASE_MISMATCH, "Skill 状态已变化，请刷新后重试");
            }
            logOperation(userId, skillId, null, "ARCHIVE", null, null);
        });
        return getOwnSkill(userId, skillId);
    }

    public SkillSummaryView restoreSkill(long userId, long skillId, long expectedRevision) {
        transactionTemplate.executeWithoutResult(status -> {
            SkillDefinitionEntity definition = definitionMapper.selectOwnedByIdForUpdate(skillId, userId);
            if (definition == null) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_NOT_OWNED);
            }
            int updated = definitionMapper.casRestore(skillId, userId, expectedRevision);
            if (updated == 0) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.ACTIVE_RELEASE_MISMATCH, "Skill 状态已变化，请刷新后重试");
            }
            logOperation(userId, skillId, null, "RESTORE", null, null);
        });
        return getOwnSkill(userId, skillId);
    }

    // ==================================================================
    // 内部：Git 文件读写
    // ==================================================================

    private String pushFiles(String directory, String branch, Map<String, byte[]> files) {
        String head = null;
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            head = gitee.putFile(directory + "/" + entry.getKey(), branch,
                    Base64.getEncoder().encodeToString(entry.getValue()), "chore(skill): init " + entry.getKey());
        }
        return head == null ? "" : head;
    }

    private void insertProposal(long skillId, String branch, String head, Long baseReleaseId, long userId) {
        SkillProposalEntity proposal = new SkillProposalEntity();
        proposal.setSkillId(skillId);
        proposal.setBaseReleaseId(baseReleaseId);
        proposal.setBranchName(branch);
        proposal.setHeadCommitSha(head);
        proposal.setRevision(1L);
        proposal.setValidationStatus(SkillProposalEntity.VALIDATION_UNVALIDATED);
        proposal.setSourceType(SkillProposalEntity.SOURCE_TYPE_USER);
        proposal.setStatus(SkillProposalEntity.STATUS_OPEN);
        proposal.setCreatedBy(userId);
        proposal.setUpdatedBy(userId);
        proposalMapper.insert(proposal);
    }

    private Map<String, byte[]> readBranchFiles(String directory, String branch) {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (GiteeSkillRepository.GiteeFile file : gitee.listFilesUnder(directory, branch)) {
            contents.put(relativePath(file.path(), directory),
                    gitee.readFile(file.path(), branch));
        }
        return contents;
    }

    private Map<String, byte[]> readTagFiles(String directory, String tagName) {
        Map<String, byte[]> contents = new LinkedHashMap<>();
        for (GiteeSkillRepository.GiteeFile file : gitee.listFilesUnder(directory, tagName)) {
            contents.put(relativePath(file.path(), directory),
                    gitee.readFile(file.path(), tagName));
        }
        return contents;
    }

    // ==================================================================
    // 内部：断言与工具
    // ==================================================================

    private SkillDefinitionEntity requireOwned(long userId, long skillId) {
        SkillDefinitionEntity definition = definitionMapper.selectOwnedById(skillId, userId);
        if (definition == null) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_NOT_OWNED);
        }
        return definition;
    }

    private SkillProposalEntity requireOpenProposal(long skillId) {
        SkillProposalEntity proposal = proposalMapper.selectOpenBySkillId(skillId);
        if (proposal == null) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.PROPOSAL_STATE_INVALID, "当前没有进行中的草稿");
        }
        return proposal;
    }

    private SkillReleaseEntity requireRelease(long skillId, long releaseId) {
        SkillReleaseEntity release = releaseMapper.selectBySkillAndId(skillId, releaseId);
        if (release == null) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_NOT_OWNED);
        }
        return release;
    }

    private SkillReleaseEntity findRelease(List<SkillReleaseEntity> releases, long releaseId) {
        return releases.stream().filter(r -> r.getId() == releaseId).findFirst().orElse(null);
    }

    private String gitDirectory(long userId, String skillKey) {
        return "users/" + userId + "/skills/" + skillKey;
    }

    private String releaseTagName(long userId, long skillId, int version) {
        return "users/" + userId + "/skills/" + skillId + "/v" + version;
    }

    private String relativePath(String fullPath, String directory) {
        return fullPath.startsWith(directory + "/")
                ? fullPath.substring(directory.length() + 1)
                : fullPath;
    }

    private long countSkillByKey(long userId, String skillKey) {
        Long count = definitionMapper.selectCount(new QueryWrapper<SkillDefinitionEntity>()
                .eq("owner_user_id", userId)
                .eq("skill_key", skillKey));
        return count == null ? 0 : count;
    }

    private Set<String> reservedKeys() {
        return properties.getSystemSkills().stream()
                .map(SkillPlatformProperties.SystemSkill::getKey)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private void safeDeleteBranch(String branch) {
        try {
            gitee.deleteBranch(branch);
        } catch (RuntimeException ex) {
            log.warn("删除 Proposal 分支失败（进入后台清理）");
        }
    }

    private ProposalView toProposalView(SkillProposalEntity proposal, Map<String, byte[]> contents) {
        List<String> errors = List.of();
        List<String> warnings = List.of();
        if (proposal.getValidationResultJson() != null
                && Objects.equals(proposal.getValidatedHeadSha(), proposal.getHeadCommitSha())) {
            try {
                Map<String, List<String>> parsed = objectMapper.readValue(
                        proposal.getValidationResultJson(), Map.class);
                errors = parsed.getOrDefault("errors", List.of());
                warnings = parsed.getOrDefault("warnings", List.of());
            } catch (RuntimeException ex) {
                log.debug("解析校验结果失败 proposalId={}", proposal.getId());
            }
        }
        List<ProposalFileView> files = contents.entrySet().stream()
                .map(entry -> new ProposalFileView(entry.getKey(), entry.getValue().length,
                        Base64.getEncoder().encodeToString(entry.getValue())))
                .toList();
        return new ProposalView(
                proposal.getId(), proposal.getHeadCommitSha(), proposal.getRevision(),
                proposal.getValidationStatus(), errors, warnings, files, proposal.getUpdatedAt());
    }

    private SkillSummaryView assembleSummary(
            SkillDefinitionEntity definition, SkillProposalEntity proposal,
            SkillReleaseEntity activeRelease, LocalDateTime lastPublishedAt
    ) {
        return new SkillSummaryView(
                definition.getId(), definition.getSkillKey(), definition.getDisplayName(),
                definition.getDescription(), definition.getSourceType(),
                Boolean.TRUE.equals(definition.getEnabled()), definition.getArchivedAt() != null,
                definition.getRevision(),
                definition.getActiveReleaseId(),
                activeRelease == null ? null : activeRelease.getVersionNumber(),
                proposal != null, proposal == null ? null : proposal.getValidationStatus(),
                lastPublishedAt, definition.getUpdatedAt());
    }

    private ReleaseSummaryView toReleaseSummary(SkillReleaseEntity release) {
        return new ReleaseSummaryView(
                release.getId(), release.getVersionNumber(),
                release.getArtifactDigest(), release.getArtifactSize(), release.getReleaseNote(),
                release.getStatus(), release.getCommitSha(), release.getCreatedAt(),
                SkillReleaseEntity.STATUS_REVOKED.equals(release.getStatus()), release.getRevokeReason());
    }

    private List<ManifestEntryView> readManifestEntries(SkillReleaseEntity release) {
        try {
            SkillBundleManifest manifest = objectMapper.readValue(release.getManifestJson(), SkillBundleManifest.class);
            return manifest.files().stream()
                    .map(entry -> new ManifestEntryView(entry.path(), entry.size(), entry.sha256()))
                    .toList();
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private List<String> readValidationWarnings(SkillReleaseEntity release) {
        try {
            Map<String, List<String>> parsed = objectMapper.readValue(
                    release.getValidationSummaryJson(), Map.class);
            return parsed == null ? List.of() : parsed.getOrDefault("warnings", List.of());
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private ArtifactDescriptor toDescriptor(SkillReleaseEntity release) {
        return new ArtifactDescriptor(1, release.getArtifactMediaType(), release.getArtifactDigest(),
                release.getArtifactSize(), release.getArtifactStore(), release.getArtifactObjectKey(),
                release.getArtifactObjectVersionId());
    }

    private SkillBundleManifest buildManifest(SkillFileSet fileSet) {
        return new SkillBundleManifest(SkillBundleManifest.SCHEMA_VERSION,
                fileSet.sortedPaths().stream()
                        .map(path -> new SkillBundleManifest.Entry(
                                path, fileSet.get(path).length,
                                DeterministicSkillTarBuilder.sha256Hex(fileSet.get(path))))
                        .toList());
    }

    private String readSkillYamlField(Map<String, byte[]> contents, String field) {
        byte[] yaml = contents.get("skill.yaml");
        if (yaml == null) {
            return null;
        }
        for (String line : new String(yaml, StandardCharsets.UTF_8).lines().toList()) {
            String stripped = line.strip();
            if (stripped.startsWith(field + ":")) {
                String value = stripped.substring(field.length() + 1).strip();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }

    private SkillPublicationOperationEntity findOrCreatePublicationOperation(
            String idempotencyKey, long skillId, long proposalId, String expectedHead
    ) {
        SkillPublicationOperationEntity existing = publicationMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            if (!Objects.equals(existing.getSkillId(), skillId)
                    || !Objects.equals(existing.getProposalId(), proposalId)
                    || !Objects.equals(existing.getExpectedProposalHead(), expectedHead)) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.OPERATION_CONFLICT,
                        "Idempotency-Key 已用于不同发布请求");
            }
            return existing;
        }
        SkillPublicationOperationEntity operation = new SkillPublicationOperationEntity();
        operation.setIdempotencyKey(idempotencyKey);
        operation.setSkillId(skillId);
        operation.setProposalId(proposalId);
        operation.setExpectedProposalHead(expectedHead);
        operation.setState(SkillPublicationOperationEntity.STATE_PREPARED);
        publicationMapper.insert(operation);
        return operation;
    }

    private void updateOperation(long id, String state, String gitCoordinates,
                                 String artifactDescriptor, String errorCode) {
        try {
            publicationMapper.updateState(id, state, gitCoordinates, artifactDescriptor, errorCode);
        } catch (RuntimeException ex) {
            log.warn("更新发布操作状态失败 operationId={} state={}", id, state);
        }
    }

    private void markOperationFailed(long id, String errorCode) {
        try {
            publicationMapper.updateState(id, SkillPublicationOperationEntity.STATE_FAILED, null, null, errorCode);
        } catch (RuntimeException ex) {
            log.warn("标记发布操作失败 operationId={}", id);
        }
    }

    private void logOperation(long userId, long skillId, Long releaseId, String operation,
                             Map<String, String> fromState, Map<String, ?> toState) {
        try {
            SkillOperationLogEntity logEntity = new SkillOperationLogEntity();
            logEntity.setOwnerUserId(userId);
            logEntity.setSkillId(skillId);
            logEntity.setReleaseId(releaseId);
            logEntity.setOperation(operation);
            logEntity.setFromStateJson(fromState == null ? null : toJson(fromState));
            logEntity.setToStateJson(toState == null ? null : toJson(toState));
            logEntity.setActorUserId(userId);
            operationLogMapper.insertLog(logEntity);
        } catch (RuntimeException ex) {
            log.error(ex.getMessage());
            log.warn("写 Skill 操作日志失败 skillId={} operation={}", skillId, operation);
        }
    }

    private int currentVersionOf(long skillId, long releaseId) {
        SkillReleaseEntity release = releaseMapper.selectBySkillAndId(skillId, releaseId);
        return release == null ? 0 : release.getVersionNumber();
    }

    private String errorCodeOf(Throwable ex) {
        if (ex instanceof SkillPlatformException platform) {
            return platform.kind().name();
        }
        return "UNKNOWN";
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (RuntimeException ex) {
            throw new IllegalStateException("JSON 序列化失败", ex);
        }
    }

    private String requireReleaseNote(String releaseNote) {
        if (releaseNote == null || releaseNote.isBlank()) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_INVALID, "发布必须填写版本说明");
        }
        String normalized = releaseNote.strip();
        if (normalized.length() > 2000) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_INVALID, "版本说明过长（上限 2000 字符）");
        }
        return normalized;
    }

    private String requireRelativePath(String path) {
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("..")
                || path.contains("\\") || path.contains("//") || path.contains(" ")) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_INVALID, "非法文件路径: " + path);
        }
        return path.strip();
    }

    private String normalizeKey(String skillKey) {
        if (skillKey == null || skillKey.isBlank()) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_INVALID, "skill_key 不能为空");
        }
        return skillKey.strip().toLowerCase(Locale.ROOT);
    }

    private String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_INVALID, field + " 不能为空");
        }
        String stripped = value.strip();
        if (stripped.length() > maxLength) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.SKILL_INVALID, field + " 过长（上限 " + maxLength + " 字符）");
        }
        return stripped;
    }

    private String normalizeText(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        return stripped.length() > maxLength ? stripped.substring(0, maxLength) : stripped;
    }

    private String defaultSkillMd(String skillKey, String displayName) {
        return """
                ---
                name: %s
                description: %s
                ---

                # %s

                在这里编写 Skill 的说明与使用方式。
                """.formatted(skillKey, displayName, displayName);
    }

    private String defaultSkillYaml(String skillKey, String displayName) {
        return """
                schemaVersion: 1
                key: %s
                displayName: %s
                capabilities:
                  scripts: false
                """.formatted(skillKey, displayName);
    }
}
