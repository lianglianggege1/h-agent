package com.h.backend.skill.application;

import com.h.backend.skill.domain.ArtifactDescriptor;
import com.h.backend.skill.domain.SkillPlatformErrorKind;
import com.h.backend.skill.domain.SkillPlatformException;
import com.h.backend.skill.infrastructure.artifact.SkillArtifactResolver;
import com.h.backend.skill.infrastructure.artifact.VerifiedSkillBundle;
import com.h.backend.skill.infrastructure.config.SkillPlatformProperties;
import com.h.backend.skill.infrastructure.persistence.entity.AgentRunSkillBindingEntity;
import com.h.backend.skill.infrastructure.persistence.entity.SkillDefinitionEntity;
import com.h.backend.skill.infrastructure.persistence.entity.SkillReleaseEntity;
import com.h.backend.skill.infrastructure.persistence.mapper.AgentRunSkillBindingMapper;
import com.h.backend.skill.infrastructure.persistence.mapper.SkillDefinitionMapper;
import com.h.backend.skill.infrastructure.persistence.mapper.SkillReleaseMapper;
import dev.langchain4j.skills.DefaultSkill;
import dev.langchain4j.skills.DefaultSkillResource;
import dev.langchain4j.skills.Skill;
import dev.langchain4j.skills.SkillResource;
import dev.langchain4j.skills.Skills;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Skill Runtime 深模块（设计 §14）：顶层 Agent 请求开始时固定 Skill 快照，
 * 本次执行期间不随发布、回滚或停用变化。
 *
 * <p>快照只按 Artifact Descriptor 读取 MinIO 或 digest 缓存，不读取 Gitee；
 * 任一必需 Artifact 无法取得或校验失败时在启动前明确失败（不变量 26）。
 * 成功解析后持久化 Binding，同一快照同时用于可用 Skill 列表、内容解析和
 * Binding 记录；禁止在 prompt 构造后再次查询“最新版”。
 *
 * <p>快照以 memoryId 为键缓存在本实例内（TTL 来自配置）：ChatService 在创建
 * run 后立即固定快照，system prompt 构造与 Skill 工具解析都读取同一份。
 * Subagent 第一期不自动加载 Skill。
 */
@Slf4j
@Service
public class SkillRuntimeService {

    /** DefaultSkillResource 只承载文本；图片等二进制资产不进入 prompt 资源。 */
    private static final Set<String> TEXT_RESOURCE_EXTENSIONS = Set.of("md", "txt", "json", "yaml", "yml");

    private final SkillDefinitionMapper definitionMapper;
    private final SkillReleaseMapper releaseMapper;
    private final AgentRunSkillBindingMapper bindingMapper;
    private final SkillArtifactResolver artifactResolver;
    private final SkillPlatformProperties properties;

    private final Map<String, TimedSnapshot> preparedByMemoryId = new ConcurrentHashMap<>();

    public SkillRuntimeService(
            SkillDefinitionMapper definitionMapper,
            SkillReleaseMapper releaseMapper,
            AgentRunSkillBindingMapper bindingMapper,
            SkillArtifactResolver artifactResolver,
            SkillPlatformProperties properties
    ) {
        this.definitionMapper = definitionMapper;
        this.releaseMapper = releaseMapper;
        this.bindingMapper = bindingMapper;
        this.artifactResolver = artifactResolver;
        this.properties = properties;
    }

    // ==================================================================
    // 视图
    // ==================================================================

    /** 快照中的一个 Skill：逻辑身份 + 已验证制品。 */
    public record SnapshotSkill(
            String sourceType,
            String skillKey,
            String displayName,
            String systemRevision,
            Long skillId,
            Long releaseId,
            ArtifactDescriptor descriptor,
            VerifiedSkillBundle bundle
    ) {
    }

    /** 一次顶层请求的不可变 Skill 快照。 */
    public static final class PreparedSnapshot {
        private final String snapshotId;
        private final long userId;
        private final List<SnapshotSkill> skills;
        private final Skills langchainSkills;
        private final String formattedOverview;

        PreparedSnapshot(String snapshotId, long userId, List<SnapshotSkill> skills,
                         Skills langchainSkills, String formattedOverview) {
            this.snapshotId = snapshotId;
            this.userId = userId;
            this.skills = List.copyOf(skills);
            this.langchainSkills = langchainSkills;
            this.formattedOverview = formattedOverview;
        }

        public String snapshotId() {
            return snapshotId;
        }

        public long userId() {
            return userId;
        }

        public List<SnapshotSkill> skills() {
            return skills;
        }

        public Skills langchainSkills() {
            return langchainSkills;
        }

        public String formattedOverview() {
            return formattedOverview;
        }

        public boolean isEmpty() {
            return skills.isEmpty();
        }
    }

    // ==================================================================
    // 快照固定与消费
    // ==================================================================

    /**
     * 顶层 Agent 请求开始时固定快照：加载并验证全部适用 Artifact（fail-closed），
     * 持久化 Binding，并按 memoryId 注册供本次执行的 prompt 与工具解析使用。
     */
    public PreparedSnapshot snapshotForTopLevelRun(long userId, Long runId, String memoryId) {
        PreparedSnapshot snapshot = buildSnapshot(userId, runId);
        if (memoryId != null && !memoryId.isBlank()) {
            preparedByMemoryId.put(memoryId, new TimedSnapshot(snapshot, Instant.now()));
            evictExpired();
        }
        return snapshot;
    }

    /** 本次执行使用的快照；未注册（如 Subagent 或无 Skill 用户）返回 null。 */
    public PreparedSnapshot findPrepared(String memoryId) {
        if (memoryId == null || memoryId.isBlank()) {
            return null;
        }
        TimedSnapshot timed = preparedByMemoryId.get(memoryId);
        if (timed == null) {
            return null;
        }
        if (timed.isExpired(ttlSeconds())) {
            preparedByMemoryId.remove(memoryId);
            return null;
        }
        return timed.snapshot();
    }

    /** system prompt 追加段：可用 Skill 列表与使用说明；无 Skill 返回 null。 */
    public String skillsSystemMessage(String memoryId) {
        PreparedSnapshot snapshot = findPrepared(memoryId);
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        return snapshot.formattedOverview();
    }

    /** Skill 工具集（activate_skill / read_skill_resource）；无 Skill 返回 null。 */
    public Skills langchainSkillsFor(String memoryId) {
        PreparedSnapshot snapshot = findPrepared(memoryId);
        return snapshot == null ? null : snapshot.langchainSkills();
    }

    // ==================================================================
    // 快照构建
    // ==================================================================

    private PreparedSnapshot buildSnapshot(long userId, Long runId) {
        String snapshotId = UUID.randomUUID().toString().replace("-", "");
        List<SnapshotSkill> collected = new ArrayList<>();

        // System Skill 来自本次部署加载的配置快照（设计 §14.1）
        for (SkillPlatformProperties.SystemSkill systemSkill : properties.getSystemSkills()) {
            if (systemSkill == null || systemSkill.getKey() == null || !systemSkill.isEnabled()) {
                continue;
            }
            SkillPlatformProperties.Artifact artifact = systemSkill.getArtifact();
            if (artifact == null || artifact.getDigest() == null || artifact.getObjectKey() == null) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.ARTIFACT_CORRUPT,
                        "系统内置 Skill 配置缺少完整 Artifact Descriptor: " + systemSkill.getKey());
            }
            ArtifactDescriptor descriptor = new ArtifactDescriptor(
                    artifact.getSchemaVersion(), artifact.getMediaType(), artifact.getDigest(),
                    artifact.getSize(), artifact.getStore(), artifact.getObjectKey(),
                    artifact.getObjectVersionId());
            VerifiedSkillBundle bundle = artifactResolver.openVerified(descriptor);
            collected.add(new SnapshotSkill(
                    AgentRunSkillBindingEntity.SOURCE_SYSTEM, systemSkill.getKey(),
                    systemSkill.getDisplayName(), systemSkill.getRevision(),
                    null, null, descriptor, bundle));
        }

        // User Skill：未归档、已启用、存在生效 Release（设计 §14.3 步骤 1）
        for (SkillDefinitionEntity definition : definitionMapper.selectSnapshotCandidates(userId)) {
            SkillReleaseEntity release = releaseMapper.selectBySkillAndId(
                    definition.getId(), definition.getActiveReleaseId());
            if (release == null) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.ARTIFACT_UNAVAILABLE,
                        "Skill 生效版本缺失: " + definition.getSkillKey());
            }
            if (!SkillReleaseEntity.STATUS_AVAILABLE.equals(release.getStatus())) {
                throw SkillPlatformException.of(SkillPlatformErrorKind.RELEASE_REVOKED,
                        "Skill 生效版本不可用: " + definition.getSkillKey());
            }
            ArtifactDescriptor descriptor = new ArtifactDescriptor(
                    1, release.getArtifactMediaType(), release.getArtifactDigest(),
                    release.getArtifactSize(), release.getArtifactStore(),
                    release.getArtifactObjectKey(), release.getArtifactObjectVersionId());
            VerifiedSkillBundle bundle = artifactResolver.openVerified(descriptor);
            collected.add(new SnapshotSkill(
                    AgentRunSkillBindingEntity.SOURCE_USER, definition.getSkillKey(),
                    definition.getDisplayName(), null,
                    definition.getId(), release.getId(), descriptor, bundle));
        }

        if (runId != null) {
            persistBindings(runId, snapshotId, collected);
        }

        Skills langchainSkills = collected.isEmpty() ? null : Skills.from(toLangchainSkills(collected));
        String overview = collected.isEmpty() ? null : formatOverview(collected);
        log.info("Skill 快照已固定 userId={} runId={} snapshotId={} skills={}",
                userId, runId, snapshotId, collected.size());
        return new PreparedSnapshot(snapshotId, userId, collected, langchainSkills, overview);
    }

    private void persistBindings(long runId, String snapshotId, List<SnapshotSkill> skills) {
        for (SnapshotSkill skill : skills) {
            try {
                AgentRunSkillBindingEntity binding = new AgentRunSkillBindingEntity();
                binding.setRunId(runId);
                binding.setSnapshotId(snapshotId);
                binding.setSourceType(skill.sourceType());
                binding.setSkillKey(skill.skillKey());
                binding.setSystemRevision(skill.systemRevision());
                binding.setSkillId(skill.skillId());
                binding.setReleaseId(skill.releaseId());
                binding.setArtifactStore(skill.descriptor().store());
                binding.setArtifactObjectKey(skill.descriptor().objectKey());
                binding.setArtifactObjectVersionId(skill.descriptor().objectVersionId());
                binding.setArtifactMediaType(skill.descriptor().mediaType());
                binding.setArtifactDigest(skill.descriptor().digest());
                binding.setArtifactSize(skill.descriptor().size());
                bindingMapper.insert(binding);
            } catch (RuntimeException ex) {
                log.error("写入 Skill Binding 失败 runId={} skillKey={}", runId, skill.skillKey(), ex);
            }
        }
    }

    private List<Skill> toLangchainSkills(List<SnapshotSkill> collected) {
        return collected.stream().map(skill -> {
            List<SkillResource> resources = skill.bundle().files().sortedPaths().stream()
                    .filter(path -> !"SKILL.md".equals(path))
                    .filter(this::isTextResource)
                    .map(path -> DefaultSkillResource.builder()
                            .relativePath(path)
                            .content(new String(skill.bundle().files().get(path), StandardCharsets.UTF_8))
                            .build())
                    .collect(Collectors.toList());
            return (Skill) DefaultSkill.builder()
                    .name(skill.skillKey())
                    .description(skill.displayName() == null ? skill.skillKey() : skill.displayName())
                    .content(skill.bundle().files().requireText("SKILL.md") == null
                            ? "" : skill.bundle().files().requireText("SKILL.md"))
                    .resources(resources)
                    .build();
        }).collect(Collectors.toList());
    }

    private boolean isTextResource(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) {
            return false;
        }
        return TEXT_RESOURCE_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase(Locale.ROOT));
    }

    private String formatOverview(List<SnapshotSkill> skills) {
        StringBuilder builder = new StringBuilder("You have access to the following skills:\n");
        for (SnapshotSkill skill : skills) {
            builder.append("- ").append(skill.skillKey())
                    .append(": ").append(skill.displayName() == null ? "" : skill.displayName())
                    .append(" (").append(skill.sourceType()).append(")\n");
        }
        builder.append("When the user's request relates to one of these skills, first call `activate_skill` ")
                .append("before following its instructions. Use `read_skill_resource` when a referenced resource ")
                .append("is needed. Skill-provided scripts must not be executed.");
        return builder.toString();
    }

    // ==================================================================
    // 缓存维护
    // ==================================================================

    private long ttlSeconds() {
        return properties.getCache().getSnapshotTtl().toSeconds();
    }

    private void evictExpired() {
        long ttl = ttlSeconds();
        Instant now = Instant.now();
        preparedByMemoryId.entrySet().removeIf(entry -> entry.getValue().isExpired(ttl, now));
    }

    private record TimedSnapshot(PreparedSnapshot snapshot, Instant createdAt) {

        boolean isExpired(long ttlSeconds) {
            return isExpired(ttlSeconds, Instant.now());
        }

        boolean isExpired(long ttlSeconds, Instant now) {
            return now.getEpochSecond() - createdAt.getEpochSecond() >= ttlSeconds;
        }
    }
}
