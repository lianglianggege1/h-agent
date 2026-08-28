package com.h.backend.skill.infrastructure.artifact;

import com.h.backend.skill.domain.ArtifactDescriptor;
import com.h.backend.skill.domain.SkillFileSet;
import com.h.backend.skill.domain.tar.SkillBundleManifest;

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
