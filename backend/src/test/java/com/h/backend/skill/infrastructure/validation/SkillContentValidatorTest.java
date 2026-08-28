package com.h.backend.skill.infrastructure.validation;

import com.h.backend.skill.domain.SkillFileSet;
import com.h.backend.skill.domain.SkillValidationResult;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SkillContentValidatorTest {

    private final SkillContentValidator validator = new SkillContentValidator(new SkillContentValidator.Quotas(
            20, 1024 * 1024, 10 * 1024 * 1024, 200, 8));

    private static Map<String, byte[]> validFiles() {
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("SKILL.md", """
                ---
                name: demo-skill
                description: Demo skill
                ---

                # Demo

                Body.
                """.getBytes(StandardCharsets.UTF_8));
        files.put("skill.yaml", """
                schemaVersion: 1
                key: demo-skill
                displayName: Demo
                capabilities:
                  scripts: false
                """.getBytes(StandardCharsets.UTF_8));
        return files;
    }

    @Test
    void validatesValidSkill() {
        SkillValidationResult result = validator.validate(
                SkillFileSet.of(validFiles()), "demo-skill", Set.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.errors()).isEmpty();
    }

    @Test
    void rejectsMissingSkillMd() {
        Map<String, byte[]> files = validFiles();
        files.remove("SKILL.md");

        SkillValidationResult result = validator.validate(SkillFileSet.of(files), "demo-skill", Set.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("SKILL.md"));
    }

    @Test
    void rejectsSkillMdWithoutFrontMatter() {
        Map<String, byte[]> files = validFiles();
        files.put("SKILL.md", "Just a body".getBytes(StandardCharsets.UTF_8));

        SkillValidationResult result = validator.validate(SkillFileSet.of(files), "demo-skill", Set.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("front matter"));
    }

    @Test
    void rejectsInvalidSkillKey() {
        SkillValidationResult result = validator.validate(
                SkillFileSet.of(validFiles()), "Bad_Key", Set.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("skill_key"));
    }

    @Test
    void rejectsReservedSystemKey() {
        SkillValidationResult result = validator.validate(
                SkillFileSet.of(validFiles()), "demo-skill", Set.of("demo-skill"));

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("系统内置"));
    }

    @Test
    void rejectsForbiddenRootFile() {
        Map<String, byte[]> files = validFiles();
        files.put("notes.txt", "x".getBytes(StandardCharsets.UTF_8));

        SkillValidationResult result = validator.validate(SkillFileSet.of(files), "demo-skill", Set.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("根目录"));
    }

    @Test
    void rejectsUnsupportedDirectoryAndExtension() {
        Map<String, byte[]> files = validFiles();
        files.put("scripts/run.sh", "echo".getBytes(StandardCharsets.UTF_8));
        files.put("references/guide.exe", "x".getBytes(StandardCharsets.UTF_8));

        SkillValidationResult result = validator.validate(SkillFileSet.of(files), "demo-skill", Set.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("scripts/run.sh"));
        assertThat(result.errors()).anyMatch(error -> error.contains("references/guide.exe"));
    }

    @Test
    void rejectsPathTraversal() {
        Map<String, byte[]> files = validFiles();
        files.put("references/../evil.md", "x".getBytes(StandardCharsets.UTF_8));

        SkillValidationResult result = validator.validate(SkillFileSet.of(files), "demo-skill", Set.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("非法文件路径"));
    }

    @Test
    void rejectsHighConfidenceCredentials() {
        Map<String, byte[]> files = validFiles();
        files.put("references/secret.md",
                "token = sk-abcdefghijklmnopqrstuvwxyz012345".getBytes(StandardCharsets.UTF_8));

        SkillValidationResult result = validator.validate(SkillFileSet.of(files), "demo-skill", Set.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("高置信度凭据"));
    }

    @Test
    void rejectsPrivateKeyBlock() {
        Map<String, byte[]> files = validFiles();
        files.put("references/key.md",
                "-----BEGIN RSA PRIVATE KEY-----".getBytes(StandardCharsets.UTF_8));

        SkillValidationResult result = validator.validate(SkillFileSet.of(files), "demo-skill", Set.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("高置信度凭据"));
    }

    @Test
    void rejectsScriptCapabilityDeclaration() {
        Map<String, byte[]> files = validFiles();
        files.put("skill.yaml", """
                schemaVersion: 1
                key: demo-skill
                displayName: Demo
                capabilities:
                  scripts: true
                """.getBytes(StandardCharsets.UTF_8));

        SkillValidationResult result = validator.validate(SkillFileSet.of(files), "demo-skill", Set.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("scripts"));
    }

    @Test
    void rejectsMismatchedSkillYamlKey() {
        Map<String, byte[]> files = validFiles();
        files.put("skill.yaml", """
                schemaVersion: 1
                key: other-skill
                displayName: Demo
                """.getBytes(StandardCharsets.UTF_8));

        SkillValidationResult result = validator.validate(SkillFileSet.of(files), "demo-skill", Set.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("不一致"));
    }

    @Test
    void remoteWriteBlockersOnlyContainSecurityAndPathIssues() {
        Map<String, byte[]> files = validFiles();
        files.put("references/notes.txt", "http://example.com plain link".getBytes(StandardCharsets.UTF_8));

        assertThat(validator.remoteWriteBlockers(SkillFileSet.of(files))).isEmpty();

        files.put("references/../../../etc/passwd", "x".getBytes(StandardCharsets.UTF_8));
        assertThat(validator.remoteWriteBlockers(SkillFileSet.of(files)))
                .anyMatch(blocker -> blocker.contains("非法文件路径"));
    }

    @Test
    void remoteWriteBlockersDetectsCredentials() {
        Map<String, byte[]> files = validFiles();
        files.put("references/leak.md",
                "-----BEGIN OPENSSH PRIVATE KEY-----".getBytes(StandardCharsets.UTF_8));

        assertThat(validator.remoteWriteBlockers(SkillFileSet.of(files)))
                .anyMatch(blocker -> blocker.contains("高置信度凭据"));
    }

    @Test
    void quotaViolationsAreReported() {
        SkillContentValidator tight = new SkillContentValidator(
                new SkillContentValidator.Quotas(20, 4, 10, 1, 8));
        Map<String, byte[]> files = validFiles();
        files.put("references/too-big.md", "123456789".getBytes(StandardCharsets.UTF_8));

        SkillValidationResult result = tight.validate(SkillFileSet.of(files), "demo-skill", Set.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.errors()).anyMatch(error -> error.contains("大小上限"));
    }
}
