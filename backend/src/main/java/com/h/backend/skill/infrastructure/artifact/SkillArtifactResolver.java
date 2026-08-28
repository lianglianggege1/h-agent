package com.h.backend.skill.infrastructure.artifact;

import com.h.backend.skill.domain.ArtifactDescriptor;

/**
 * Skill 运行制品解析器（设计 §10.4）：只读深模块。
 *
 * <p>按 Descriptor 的逻辑 store 解析物理 Bucket，先查本实例 digest 缓存，
 * cache miss 时从 MinIO 流式下载并校验。不提供任意 key 的 put/get/delete/list。
 */
public interface SkillArtifactResolver {

    /** 打开并验证制品；失败抛 SkillPlatformException（UNAVAILABLE/CORRUPT）。 */
    VerifiedSkillBundle openVerified(ArtifactDescriptor descriptor);

    /** 仅验证制品可取得且完整，不解析内容；用于生效/启用前的可用性验证。 */
    void verifyAvailable(ArtifactDescriptor descriptor);
}
