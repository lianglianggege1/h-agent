package com.h.backend.skill.infrastructure.artifact;

import com.h.backend.skill.domain.ArtifactDescriptor;

/**
 * User Skill 运行制品发布器（设计 §10.4）：只暴露 Skill 需要的写能力。
 *
 * <p>输入是已通过发布校验的确定性 bundle 字节；内部隐藏内容寻址 key、
 * MinIO Client、create-only PUT、读回验证和 SDK 异常映射。发布器无法
 * 选择 System namespace，也不提供普通 delete。
 */
public interface SkillArtifactPublisher {

    /**
     * 把 canonical bundle 写入 owner 命名空间下的内容寻址 key，读回验证
     * size 与 SHA-256 后返回不可变 Descriptor。
     *
     * <p>同一 owner 内相同 digest 幂等复用：仅在实际字节验证一致时返回既有对象。
     */
    ArtifactDescriptor storeVerifiedUserBundle(long ownerUserId, byte[] bundle);
}
