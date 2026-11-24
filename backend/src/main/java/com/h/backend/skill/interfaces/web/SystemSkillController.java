package com.h.backend.skill.interfaces.web;

import com.h.backend.common.api.ApiResponse;
import com.h.capabilities.common.exception.BusinessException;
import com.h.capabilities.skill.infrastructure.artifact.BundledSystemSkillArtifacts;
import com.h.capabilities.skill.infrastructure.config.SkillPlatformProperties;
import com.h.capabilities.skill.infrastructure.config.SkillPlatformProperties.SystemSkill;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/me/skills/system")
public class SystemSkillController {
    private final SkillPlatformProperties properties;
    private final BundledSystemSkillArtifacts artifacts;

    public SystemSkillController(SkillPlatformProperties properties, BundledSystemSkillArtifacts artifacts) {
        this.properties = properties;
        this.artifacts = artifacts;
    }

    @GetMapping
    public ApiResponse<List<Summary>> list() {
        return ApiResponse.ok(bundledSkills().map(SystemSkillController::summary).toList());
    }

    @GetMapping("/{skillKey}")
    public ApiResponse<Detail> get(@PathVariable String skillKey) {
        SystemSkill skill = bundledSkills()
                .filter(item -> skillKey.equals(item.getKey()))
                .findFirst()
                .orElseThrow(() -> new BusinessException(40404, "系统内置 Skill 不存在"));
        var files = artifacts.open(skill.getKey()).files();
        var content = files.sortedPaths().stream()
                .map(path -> new SkillFile(path, files.requireText(path)))
                .toList();
        return ApiResponse.ok(new Detail(summary(skill), content));
    }

    private Stream<SystemSkill> bundledSkills() {
        return properties.getSystemSkills().stream()
                .filter(skill -> skill != null && skill.isBundled() && StringUtils.hasText(skill.getKey()));
    }

    private static Summary summary(SystemSkill skill) {
        String name = StringUtils.hasText(skill.getDisplayName()) ? skill.getDisplayName() : skill.getKey();
        return new Summary(skill.getKey(), name, skill.getRevision(), skill.isEnabled());
    }

    public record Summary(String skillKey, String displayName, String revision, boolean enabled) {}

    public record Detail(Summary summary, List<SkillFile> files) {}

    public record SkillFile(String path, String content) {}
}
