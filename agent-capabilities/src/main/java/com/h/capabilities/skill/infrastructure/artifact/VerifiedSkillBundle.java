package com.h.capabilities.skill.infrastructure.artifact;

import com.h.capabilities.skill.domain.ArtifactDescriptor;
import com.h.capabilities.skill.domain.SkillFileSet;
import com.h.capabilities.skill.domain.tar.SkillBundleManifest;

/**
 * 读回验证通过的 Skill bundle：digest、size 与 media type 已核对，
 * manifest 与文件内容已复核。Runtime 只消费该形态。
 */
public record VerifiedSkillBundle(
        ArtifactDescriptor descriptor,
        SkillFileSet files,
        SkillBundleManifest manifest
) {
}
