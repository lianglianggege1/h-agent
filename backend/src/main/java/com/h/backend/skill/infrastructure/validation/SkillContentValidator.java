package com.h.backend.skill.infrastructure.validation;

import com.h.backend.skill.domain.SkillFileSet;
import com.h.backend.skill.domain.SkillValidationResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

public class SkillContentValidator {

    public static final String VALIDATION_POLICY_VERSION = "skill-validation-v1";
    public static final String SECURITY_POLICY_VERSION = "skill-security-v1";

    private static final Pattern SKILL_KEY_PATTERN = Pattern.compile("^[a-z][a-z0-9-]{1,62}$");
    private static final Set<String> ALLOWED_TEXT_EXTENSIONS = Set.of("md", "txt", "json", "yaml", "yml");
    private static final Set<String> ALLOWED_BINARY_EXTENSIONS = Set.of("png", "jpg", "jpeg", "webp", "gif");
    private static final Set<String> FORBIDDEN_SEGMENTS = Set.of(".git", ".gitee", "node_modules", "__pycache__");
    private static final List<Pattern> HIGH_CONFIDENCE_CREDENTIAL_PATTERNS = List.of(
            Pattern.compile("-----BEGIN (?:RSA |EC |DSA |OPENSSH |PGP )?PRIVATE KEY-----"),
            Pattern.compile("\\bAKIA[0-9A-Z]{16}\\b"),
            Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,255}\\b"),
            Pattern.compile("\\bsk-[A-Za-z0-9_-]{20,}\\b"),
            Pattern.compile("\\bxox[baprs]-[A-Za-z0-9-]{10,}\\b"),
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{10,}\\b"),
            Pattern.compile("(?i)\\b(?:api[_-]?key|secret[_-]?key|access[_-]?token|password)\\s*[:=]\\s*['\\\"]?[A-Za-z0-9/+_-]{32,}['\\\"]?\\s*$")
    );

    private static final Set<String> TEXT_PREFIXES = Set.of("references/");

    private final Quotas quotas;

    public SkillContentValidator(Quotas quotas) {
        this.quotas = quotas;
    }

    public record Quotas(
            int maxUserSkills,
            long maxFileBytes,
            long maxTotalBytes,
            int maxFiles,
            int maxDepth
    ) {
    }

    public SkillValidationResult validate(SkillFileSet fileSet, String expectedSkillKey, Set<String> reservedKeys) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        validateKey(expectedSkillKey, reservedKeys, errors);
        validateStructure(fileSet, errors);
        validatePathsAndTypes(fileSet, errors, warnings);
        validateQuotas(fileSet, errors);
        validateCredentials(fileSet, errors, warnings);
        validateSkillYaml(fileSet, expectedSkillKey, errors, warnings);

        if (errors.isEmpty()) {
            return SkillValidationResult.ok(warnings, null);
        }
        return SkillValidationResult.invalid(errors, warnings, null);
    }

    /**
     * 推送远端前的最低安全检查（设计 §9.3）：只阻断路径穿越/非法路径与
     * 高置信度凭据；业务格式错误允许保存为远端草稿。
     */
    public List<String> remoteWriteBlockers(SkillFileSet fileSet) {
        List<String> blockers = new ArrayList<>();
        for (String path : fileSet.paths()) {
            if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains("..")
                    || path.contains("//") || path.contains(" ")
                    || path.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
                blockers.add("非法文件路径: " + path);
            }
            for (String segment : path.split("/")) {
                if (FORBIDDEN_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
                    blockers.add("禁止的路径段: " + path);
                }
            }
        }
        validateCredentials(fileSet, blockers, new ArrayList<>());
        return blockers;
    }

    private void validateKey(String skillKey, Set<String> reservedKeys, List<String> errors) {
        if (skillKey == null || !SKILL_KEY_PATTERN.matcher(skillKey).matches()) {
            errors.add("skill_key 必须是 2-63 位的 kebab-case（小写字母开头，仅小写字母/数字/连字符）");
            return;
        }
        if (reservedKeys.contains(skillKey)) {
            errors.add("skill_key 与系统内置 Skill 冲突：" + skillKey);
        }
    }

    private void validateStructure(SkillFileSet fileSet, List<String> errors) {
        if (fileSet.get("SKILL.md") == null) {
            errors.add("必须存在唯一的 SKILL.md");
            return;
        }
        String skillMd = fileSet.requireText("SKILL.md");
        String trimmed = skillMd.strip();
        if (!trimmed.startsWith("---")) {
            errors.add("SKILL.md 必须以 YAML front matter 开始");
            return;
        }
        int closing = trimmed.indexOf("\n---", 3);
        if (closing < 0) {
            errors.add("SKILL.md 的 YAML front matter 未闭合");
            return;
        }
        String frontMatter = trimmed.substring(3, closing);
        String body = trimmed.substring(closing + 4).strip();
        if (frontMatter.lines().noneMatch(line -> line.matches("name:\\s*\\S+"))) {
            errors.add("SKILL.md front matter 缺少 name");
        }
        if (frontMatter.lines().noneMatch(line -> line.matches("description:\\s*\\S.*"))) {
            errors.add("SKILL.md front matter 缺少 description");
        }
        if (body.isEmpty()) {
            errors.add("SKILL.md 正文为空");
        }
    }

    private void validatePathsAndTypes(SkillFileSet fileSet, List<String> errors, List<String> warnings) {
        for (String path : fileSet.paths()) {
            if (path.isEmpty() || path.startsWith("/") || path.contains("\\") || path.contains("..")
                    || path.contains("//") || path.contains(" ")) {
                errors.add("非法文件路径: " + path);
                continue;
            }
            if (path.chars().anyMatch(c -> c < 0x20 || c == 0x7F)) {
                errors.add("文件路径包含控制字符: " + path);
                continue;
            }
            List<String> segments = List.of(path.split("/"));
            for (String segment : segments) {
                if (FORBIDDEN_SEGMENTS.contains(segment.toLowerCase(Locale.ROOT))) {
                    errors.add("禁止的路径段: " + path);
                }
            }
            if (segments.size() > quotas.maxDepth()) {
                errors.add("路径深度超过上限 " + quotas.maxDepth() + ": " + path);
            }
            if (path.endsWith("/") || segments.getLast().isEmpty()) {
                errors.add("非法文件路径（空文件名）: " + path);
                continue;
            }
            String fileName = segments.getLast();
            boolean isRootFile = segments.size() == 1;
            if (isRootFile && !"SKILL.md".equals(path) && !"skill.yaml".equals(path)) {
                errors.add("根目录只允许 SKILL.md 和 skill.yaml，非法文件: " + path);
                continue;
            }
            if (!isRootFile && !path.startsWith("references/") && !path.startsWith("assets/")) {
                errors.add("只允许 references/** 与 assets/** 子目录，非法路径: " + path);
                continue;
            }
            int dot = fileName.lastIndexOf('.');
            String extension = dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
            if (!ALLOWED_TEXT_EXTENSIONS.contains(extension) && !ALLOWED_BINARY_EXTENSIONS.contains(extension)) {
                errors.add("不允许的文件类型: " + path);
                continue;
            }
            if ("skill.yaml".equals(path) && !extension.equals("yaml") && !extension.equals("yml")) {
                errors.add("skill.yaml 必须使用 yaml 扩展名");
            }
            byte[] content = fileSet.get(path);
            if (content.length == 0) {
                errors.add("空文件不被允许: " + path);
            }
            if (content.length > quotas.maxFileBytes()) {
                errors.add("文件超过大小上限 " + quotas.maxFileBytes() + " 字节: " + path);
            }
            if (isProbablyText(extension) && !isValidUtf8(content)) {
                errors.add("文本文件不是合法 UTF-8: " + path);
            }
            if (path.startsWith("assets/") && ALLOWED_TEXT_EXTENSIONS.contains(extension)) {
                warnings.add("assets/ 下建议只放静态图片，文本文件请放 references/: " + path);
            }
        }
    }

    private void validateQuotas(SkillFileSet fileSet, List<String> errors) {
        if (fileSet.paths().size() > quotas.maxFiles()) {
            errors.add("文件数量超过上限 " + quotas.maxFiles());
        }
        if (fileSet.totalBytes() > quotas.maxTotalBytes()) {
            errors.add("总大小超过上限 " + quotas.maxTotalBytes() + " 字节");
        }
    }

    private void validateCredentials(SkillFileSet fileSet, List<String> errors, List<String> warnings) {
        for (String path : fileSet.paths()) {
            String extension = extensionOf(path);
            if (!isProbablyText(extension)) {
                continue;
            }
            String content = fileSet.requireText(path);
            for (Pattern pattern : HIGH_CONFIDENCE_CREDENTIAL_PATTERNS) {
                if (pattern.matcher(content).find()) {
                    errors.add("检测到高置信度凭据或私钥，禁止保存到远端（请轮换已泄漏凭据）: " + path);
                    break;
                }
            }
            if (content.lines().filter(line -> line.strip().startsWith("http://")).count() > 0) {
                warnings.add("存在 http:// 明文链接: " + path);
            }
        }
    }

    private void validateSkillYaml(SkillFileSet fileSet, String expectedSkillKey, List<String> errors, List<String> warnings) {
        String yaml = fileSet.requireText("skill.yaml");
        if (yaml == null) {
            warnings.add("缺少 skill.yaml（推荐补充 displayName 声明）");
            return;
        }
        String key = null;
        String displayName = null;
        String scriptsFlag = null;
        for (String line : yaml.lines().toList()) {
            String stripped = line.strip();
            if (stripped.startsWith("key:")) {
                key = stripped.substring(4).strip();
            } else if (stripped.startsWith("displayName:")) {
                displayName = stripped.substring(12).strip();
            } else if (stripped.startsWith("scripts:")) {
                scriptsFlag = stripped.substring(8).strip();
            }
        }
        if (key != null && !key.equals(expectedSkillKey)) {
            errors.add("skill.yaml 的 key 与 Skill 身份不一致: " + key);
        }
        if (displayName == null || displayName.isEmpty()) {
            warnings.add("skill.yaml 缺少 displayName");
        }
        if ("true".equalsIgnoreCase(scriptsFlag)) {
            errors.add("skill.yaml 声明 scripts: true，平台不允许脚本");
        }
    }

    private boolean isProbablyText(String extension) {
        return ALLOWED_TEXT_EXTENSIONS.contains(extension);
    }

    private boolean isValidUtf8(byte[] content) {
        java.nio.charset.CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        try {
            decoder.decode(java.nio.ByteBuffer.wrap(content));
            return true;
        } catch (java.nio.charset.CharacterCodingException ex) {
            return false;
        }
    }

    private String extensionOf(String path) {
        int slash = path.lastIndexOf('/');
        String fileName = slash < 0 ? path : path.substring(slash + 1);
        int dot = fileName.lastIndexOf('.');
        return dot < 0 ? "" : fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public Set<String> allowedTextExtensions() {
        return ALLOWED_TEXT_EXTENSIONS;
    }

    public Map<String, Set<String>> allowedExtensions() {
        return Map.of(
                "text", ALLOWED_TEXT_EXTENSIONS,
                "binary", ALLOWED_BINARY_EXTENSIONS);
    }
}
