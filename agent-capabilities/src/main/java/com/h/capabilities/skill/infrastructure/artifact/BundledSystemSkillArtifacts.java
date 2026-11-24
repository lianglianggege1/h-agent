package com.h.capabilities.skill.infrastructure.artifact;

import com.h.capabilities.skill.domain.ArtifactDescriptor;
import com.h.capabilities.skill.domain.SkillFileSet;
import com.h.capabilities.skill.domain.SkillPlatformErrorKind;
import com.h.capabilities.skill.domain.SkillPlatformException;
import com.h.capabilities.skill.domain.tar.DeterministicSkillTarBuilder;
import com.h.capabilities.skill.domain.tar.SkillTarReader;
import com.h.capabilities.skill.infrastructure.validation.SkillContentValidator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class BundledSystemSkillArtifacts {
    private static final String STORE = "bundled-system-skill-artifacts";

    private final SkillContentValidator validator;
    private final DeterministicSkillTarBuilder builder;
    private final SkillTarReader reader;
    private final Map<String, VerifiedSkillBundle> bundles = new ConcurrentHashMap<>();

    public BundledSystemSkillArtifacts(SkillContentValidator validator, DeterministicSkillTarBuilder builder,
                                       SkillTarReader reader) {
        this.validator = validator;
        this.builder = builder;
        this.reader = reader;
    }

    public VerifiedSkillBundle open(String skillKey) {
        if (!skillKey.matches("[a-z][a-z0-9-]{1,62}")) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.ARTIFACT_CORRUPT, "系统内置 Skill 标识无效");
        }
        return bundles.computeIfAbsent(skillKey, this::load);
    }

    private VerifiedSkillBundle load(String key) {
        String path = "system-skills/" + key + "/";
        SkillFileSet files;
        try {
            files = SkillFileSet.of(Map.of(
                    "SKILL.md", new ClassPathResource(path + "SKILL.md").getContentAsByteArray(),
                    "skill.yaml", new ClassPathResource(path + "skill.yaml").getContentAsByteArray()));
        } catch (IOException ex) {
            throw new SkillPlatformException(SkillPlatformErrorKind.ARTIFACT_UNAVAILABLE,
                    "发布包缺少系统内置 Skill: " + key, ex);
        }
        var result = validator.validate(files, key, Set.of());
        if (!result.valid()) {
            throw SkillPlatformException.of(SkillPlatformErrorKind.ARTIFACT_CORRUPT,
                    "系统内置 Skill 校验失败: " + key + " " + result.errors());
        }
        byte[] bytes = builder.build(files);
        var parsed = reader.parse(bytes);
        var descriptor = ArtifactDescriptor.of("sha256:" + DeterministicSkillTarBuilder.sha256Hex(bytes),
                bytes.length, STORE, path, null);
        return new VerifiedSkillBundle(descriptor, parsed.files(), parsed.manifest());
    }
}
