package com.h.backend.chat.domain.subagentdefinition;

import com.h.backend.chat.domain.subagentdefinition.model.CapabilityDeclaration;
import com.h.backend.chat.domain.subagentdefinition.model.CompileOutcome;
import com.h.backend.chat.domain.subagentdefinition.model.CompiledSubagentDefinition;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionSource;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentRuntimeKind;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentWorkspaceMode;
import com.h.backend.chat.domain.subagentdefinition.model.ValidationIssue;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.Mark;
import org.yaml.snakeyaml.error.MarkedYAMLException;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.Node;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.SequenceNode;
import org.yaml.snakeyaml.nodes.Tag;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Subagent Markdown 定义的严格编译器。
 *
 * <p>校验阶段：大小校验 → front matter 与正文分离 → YAML 语法与未知字段 →
 * 字段类型、长度、枚举 → 来源规则（BUILTIN / USER）→ 规范化编译 → SHA-256 content hash。
 * capability / ownership / quota 属于 Catalog 层，不在编译器内。</p>
 *
 * <p>实现约束：日志与 issue 不携带完整用户 Markdown；issue 尽量带 front matter
 * 内的 1 起始绝对行号（列号同理）。</p>
 */
public final class SubagentMarkdownCompiler {

    /** Markdown 原文最大 32 KiB。 */
    public static final int MAX_MARKDOWN_BYTES = 32 * 1024;

    private static final String FRONT_MATTER_DELIMITER = "---";
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "display_name", "description", "mode", "model", "steps", "tools", "skills", "workspace");
    private static final Set<String> ALLOWED_WORKSPACE_FIELDS = Set.of("mode");

    private static final int DISPLAY_NAME_MAX = 80;
    private static final int DESCRIPTION_MAX = 500;
    private static final int STEPS_MIN = 1;
    private static final int STEPS_MAX = 20;
    private static final int STEPS_DEFAULT = 10;

    /**
     * 编译入口。
     *
     * @param rawMarkdown   原始 Markdown（发布原文）
     * @param source        来源规则：BUILTIN 或 USER
     * @param allowedTools  平台允许的工具名集合（default + requestable）
     * @param allowedSkills 当前上下文可访问的 Skill 名集合；第一期通常为空
     */
    public CompileOutcome compile(
            String rawMarkdown,
            SubagentDefinitionSource source,
            Set<String> allowedTools,
            Set<String> allowedSkills) {
        List<ValidationIssue> issues = new ArrayList<>();

        if (rawMarkdown == null || rawMarkdown.isBlank()) {
            issues.add(ValidationIssue.error("FRONT_MATTER_MISSING", "front-matter",
                    "Markdown 不能为空，且必须以 --- 开始的 front matter"));
            return CompileOutcome.failed(issues);
        }
        if (rawMarkdown.getBytes(StandardCharsets.UTF_8).length > MAX_MARKDOWN_BYTES) {
            issues.add(ValidationIssue.error("SIZE_EXCEEDED", "markdown",
                    "Markdown 超过 32 KiB 上限"));
            return CompileOutcome.failed(issues);
        }

        String text = stripBom(rawMarkdown);
        FrontMatterSplit split = splitFrontMatter(text);
        if (split.error() != null) {
            issues.add(split.error());
            return CompileOutcome.failed(issues);
        }

        Fields fields = parseFrontMatter(split.frontMatter(), issues);
        String body = split.body().strip();

        String displayName = null;
        String description = null;
        Integer steps = null;
        CapabilityDeclaration tools = null;
        CapabilityDeclaration skills = null;
        SubagentWorkspaceMode workspaceMode = null;

        if (fields != null) {
            displayName = requireString(fields, "display_name", DISPLAY_NAME_MAX, issues);
            description = requireString(fields, "description", DESCRIPTION_MAX, issues);
            optionalEnum(fields, "mode", Set.of(CompiledSubagentDefinition.MODE_SUBAGENT), issues);
            optionalEnum(fields, "model", Set.of(CompiledSubagentDefinition.MODEL_INHERIT), issues);
            steps = optionalSteps(fields, issues);
            tools = optionalNameList(fields, "tools", allowedTools, "TOOL_NOT_ALLOWED",
                    "不在平台允许的工具集合内", issues);
            skills = optionalNameList(fields, "skills", allowedSkills, "SKILL_NOT_ACCESSIBLE",
                    "不在当前可访问的 Skill 集合内", issues);
            workspaceMode = optionalWorkspaceMode(fields, source, issues);
        }

        if (body.isEmpty()) {
            issues.add(ValidationIssue.error("EMPTY_BODY", "body", 1,
                    "正文不能为空，将作为 child system prompt 使用"));
        }

        if (issues.stream().anyMatch(i -> i.severity() == ValidationIssue.Severity.ERROR)) {
            return CompileOutcome.failed(issues);
        }

        String systemPrompt = body;
        CompiledSubagentDefinition compiled = new CompiledSubagentDefinition(
                displayName,
                description,
                CompiledSubagentDefinition.MODE_SUBAGENT,
                CompiledSubagentDefinition.MODEL_INHERIT,
                steps == null ? STEPS_DEFAULT : steps,
                tools == null ? CapabilityDeclaration.OMITTED : tools,
                skills == null ? CapabilityDeclaration.OMITTED : skills,
                workspaceMode == null
                        ? (source == SubagentDefinitionSource.BUILTIN
                                ? SubagentWorkspaceMode.SHARED
                                : SubagentWorkspaceMode.ISOLATED)
                        : workspaceMode,
                SubagentRuntimeKind.CATALOG_DECLARATION,
                systemPrompt);

        String normalized = normalizeForHash(text);
        return new CompileOutcome(issues, compiled, sha256Hex(normalized), normalized);
    }

    // ---------- front matter 分离 ----------

    private record FrontMatterSplit(String frontMatter, String body, ValidationIssue error) {
    }

    private FrontMatterSplit splitFrontMatter(String text) {
        String[] lines = text.split("\n", -1);
        if (lines.length == 0 || !FRONT_MATTER_DELIMITER.equals(lines[0].strip())) {
            return new FrontMatterSplit(null, null, ValidationIssue.error(
                    "FRONT_MATTER_MISSING", "front-matter", 1,
                    "必须以 --- 行开始 front matter"));
        }
        for (int i = 1; i < lines.length; i++) {
            if (FRONT_MATTER_DELIMITER.equals(lines[i].strip())) {
                String frontMatter = String.join("\n",
                        List.of(lines).subList(1, i));
                String body = String.join("\n",
                        List.of(lines).subList(Math.min(i + 1, lines.length), lines.length));
                return new FrontMatterSplit(frontMatter, body, null);
            }
        }
        return new FrontMatterSplit(null, null, ValidationIssue.error(
                "FRONT_MATTER_UNCLOSED", "front-matter", 1,
                "front matter 缺少结束的 --- 行"));
    }

    // ---------- YAML 解析 ----------

    private static final class Fields {
        final List<NodeTuple> tuples = new ArrayList<>();

        record NodeTuple(String key, ScalarNode keyNode, Node valueNode) {
        }
    }

    private Fields parseFrontMatter(String frontMatter, List<ValidationIssue> issues) {
        if (frontMatter.isBlank()) {
            issues.add(ValidationIssue.error("MISSING_FIELD", "display_name", 2,
                    "缺少必填字段 display_name"));
            issues.add(ValidationIssue.error("MISSING_FIELD", "description", 2,
                    "缺少必填字段 description"));
            return null;
        }
        try {
            Yaml yaml = new Yaml(new LoaderOptions());
            Node node = yaml.compose(new StringReader(frontMatter));
            if (node == null) {
                issues.add(ValidationIssue.error("MISSING_FIELD", "display_name", 2,
                        "缺少必填字段 display_name"));
                issues.add(ValidationIssue.error("MISSING_FIELD", "description", 2,
                        "缺少必填字段 description"));
                return null;
            }
            if (!(node instanceof MappingNode mapping)) {
                issues.add(ValidationIssue.error("INVALID_TYPE", "front-matter",
                        absoluteLine(node.getStartMark()),
                        "front matter 必须是字段映射，而不是列表或标量"));
                return null;
            }
            Fields fields = new Fields();

            Set<String> seen = new HashSet<>();
            for (NodeTupleYaml tuple : yamlTuples(mapping)) {
                if (!(tuple.keyNode() instanceof ScalarNode keyScalar)) {
                    issues.add(ValidationIssue.error("INVALID_TYPE", "front-matter",
                            absoluteLine(tuple.keyNode().getStartMark()),
                            "字段名必须是字符串"));
                    continue;
                }
                String key = keyScalar.getValue();
                if (!seen.add(key)) {
                    issues.add(ValidationIssue.error("DUPLICATE_FIELD", key,
                            absoluteLine(keyScalar.getStartMark()),
                            "字段重复出现"));
                    continue;
                }
                if (!ALLOWED_FIELDS.contains(key)) {
                    issues.add(ValidationIssue.error("UNKNOWN_FIELD", key,
                            absoluteLine(keyScalar.getStartMark()),
                            keyScalar.getStartMark().getColumn() + 1,
                            "未知字段，第一期不支持（含 provider/MCP/权限扩展等配置）"));
                    continue;
                }
                fields.tuples.add(new Fields.NodeTuple(key, keyScalar, tuple.valueNode()));
            }
            return fields;
        } catch (MarkedYAMLException e) {
            Mark mark = e.getProblemMark();
            issues.add(ValidationIssue.error("YAML_PARSE_ERROR", "front-matter",
                    mark == null ? null : mark.getLine() + 2,
                    "front matter 不是合法 YAML: " + safeParseMessage(e)));
            return null;
        }
    }

    private record NodeTupleYaml(ScalarNode keyNode, Node valueNode) {
    }

    private List<NodeTupleYaml> yamlTuples(MappingNode mapping) {
        List<NodeTupleYaml> result = new ArrayList<>();
        for (org.yaml.snakeyaml.nodes.NodeTuple t : mapping.getValue()) {
            if (t.getKeyNode() instanceof ScalarNode scalar) {
                result.add(new NodeTupleYaml(scalar, t.getValueNode()));
            } else {
                result.add(new NodeTupleYaml(null, t.getValueNode()));
            }
        }
        return result;
    }

    // ---------- 字段校验 ----------

    private String requireString(Fields fields, String name, int maxLength, List<ValidationIssue> issues) {
        Fields.NodeTuple tuple = fields.tuples.stream()
                .filter(t -> t.key().equals(name)).findFirst().orElse(null);
        if (tuple == null) {
            issues.add(ValidationIssue.error("MISSING_FIELD", name,
                    "缺少必填字段 " + name));
            return null;
        }
        if (!(tuple.valueNode() instanceof ScalarNode scalar) || !Tag.STR.equals(resolvedTag(scalar))) {
            issues.add(ValidationIssue.error("INVALID_TYPE", name,
                    absoluteLine(tuple.valueNode().getStartMark()),
                    name + " 必须是字符串"));
            return null;
        }
        String value = scalar.getValue();
        if (value.strip().isEmpty()) {
            issues.add(ValidationIssue.error("INVALID_VALUE", name,
                    absoluteLine(tuple.valueNode().getStartMark()),
                    name + " 不能为空白"));
            return null;
        }
        int length = value.codePointCount(0, value.length());
        if (length > maxLength) {
            issues.add(ValidationIssue.error("LENGTH_EXCEEDED", name,
                    absoluteLine(tuple.valueNode().getStartMark()),
                    name + " 长度超过上限 " + maxLength + " 字符"));
            return null;
        }
        return value.strip();
    }

    private String optionalEnum(Fields fields, String name, Set<String> allowed, List<ValidationIssue> issues) {
        Fields.NodeTuple tuple = fields.tuples.stream()
                .filter(t -> t.key().equals(name)).findFirst().orElse(null);
        if (tuple == null) {
            return null;
        }
        if (!(tuple.valueNode() instanceof ScalarNode scalar) || !Tag.STR.equals(resolvedTag(scalar))) {
            issues.add(ValidationIssue.error("INVALID_TYPE", name,
                    absoluteLine(tuple.valueNode().getStartMark()),
                    name + " 必须是字符串"));
            return null;
        }
        String value = scalar.getValue();
        if (!allowed.contains(value)) {
            issues.add(ValidationIssue.error("INVALID_VALUE", name,
                    absoluteLine(tuple.valueNode().getStartMark()),
                    name + " 只允许 " + allowed + "，当前为 " + value));
            return null;
        }
        return value;
    }

    private Integer optionalSteps(Fields fields, List<ValidationIssue> issues) {
        Fields.NodeTuple tuple = fields.tuples.stream()
                .filter(t -> t.key().equals("steps")).findFirst().orElse(null);
        if (tuple == null) {
            return null;
        }
        if (!(tuple.valueNode() instanceof ScalarNode scalar) || !Tag.INT.equals(resolvedTag(scalar))) {
            issues.add(ValidationIssue.error("INVALID_TYPE", "steps",
                    absoluteLine(tuple.valueNode().getStartMark()),
                    "steps 必须是整数"));
            return null;
        }
        int value;
        try {
            value = Integer.parseInt(scalar.getValue());
        } catch (NumberFormatException e) {
            issues.add(ValidationIssue.error("INVALID_VALUE", "steps",
                    absoluteLine(tuple.valueNode().getStartMark()),
                    "steps 必须是 1–20 的整数"));
            return null;
        }
        if (value < STEPS_MIN || value > STEPS_MAX) {
            issues.add(ValidationIssue.error("INVALID_VALUE", "steps",
                    absoluteLine(tuple.valueNode().getStartMark()),
                    "steps 必须在 " + STEPS_MIN + "–" + STEPS_MAX + " 范围内"));
            return null;
        }
        return value;
    }

    private CapabilityDeclaration optionalNameList(
            Fields fields, String name, Set<String> allowed, String notAllowedCode,
            String notAllowedMessage, List<ValidationIssue> issues) {
        Fields.NodeTuple tuple = fields.tuples.stream()
                .filter(t -> t.key().equals(name)).findFirst().orElse(null);
        if (tuple == null) {
            return null;
        }
        if (!(tuple.valueNode() instanceof SequenceNode sequence)) {
            issues.add(ValidationIssue.error("INVALID_TYPE", name,
                    absoluteLine(tuple.valueNode().getStartMark()),
                    name + " 必须是字符串数组"));
            return null;
        }
        List<String> names = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<org.yaml.snakeyaml.nodes.Node> items = sequence.getValue();
        for (int i = 0; i < items.size(); i++) {
            Node item = items.get(i);
            String field = name + "[" + i + "]";
            if (!(item instanceof ScalarNode scalar) || !Tag.STR.equals(resolvedTag(scalar))) {
                issues.add(ValidationIssue.error("INVALID_TYPE", field,
                        absoluteLine(item.getStartMark()),
                        field + " 必须是字符串"));
                continue;
            }
            String value = scalar.getValue();
            if (allowed == null || !allowed.contains(value)) {
                issues.add(ValidationIssue.error(notAllowedCode, field,
                        absoluteLine(item.getStartMark()),
                        value + " " + notAllowedMessage));
                continue;
            }
            if (!seen.add(value)) {
                issues.add(ValidationIssue.warning("DUPLICATE_ENTRY", field,
                        absoluteLine(item.getStartMark()),
                        value + " 重复声明，已忽略重复项"));
                continue;
            }
            names.add(value);
        }
        if (names.isEmpty()) {
            return CapabilityDeclaration.empty();
        }
        return CapabilityDeclaration.explicit(names);
    }

    private SubagentWorkspaceMode optionalWorkspaceMode(
            Fields fields, SubagentDefinitionSource source, List<ValidationIssue> issues) {
        Fields.NodeTuple tuple = fields.tuples.stream()
                .filter(t -> t.key().equals("workspace")).findFirst().orElse(null);
        if (tuple == null) {
            if (source == SubagentDefinitionSource.BUILTIN) {
                issues.add(ValidationIssue.error("MISSING_FIELD", "workspace.mode",
                        "内置定义必须显式声明 workspace.mode: shared"));
            }
            return null;
        }
        if (!(tuple.valueNode() instanceof MappingNode mapping)) {
            issues.add(ValidationIssue.error("INVALID_TYPE", "workspace",
                    absoluteLine(tuple.valueNode().getStartMark()),
                    "workspace 必须是映射"));
            return null;
        }
        SubagentWorkspaceMode result = null;
        boolean modeSeen = false;
        for (org.yaml.snakeyaml.nodes.NodeTuple t : mapping.getValue()) {
            if (!(t.getKeyNode() instanceof ScalarNode keyScalar)) {
                issues.add(ValidationIssue.error("INVALID_TYPE", "workspace",
                        absoluteLine(t.getKeyNode().getStartMark()),
                        "workspace 子字段名必须是字符串"));
                continue;
            }
            String key = keyScalar.getValue();
            if (!ALLOWED_WORKSPACE_FIELDS.contains(key)) {
                issues.add(ValidationIssue.error("UNKNOWN_FIELD", "workspace." + key,
                        absoluteLine(keyScalar.getStartMark()),
                        keyScalar.getStartMark().getColumn() + 1,
                        "workspace 不支持子字段 " + key));
                continue;
            }
            if (modeSeen) {
                issues.add(ValidationIssue.error("DUPLICATE_FIELD", "workspace.mode",
                        absoluteLine(keyScalar.getStartMark()),
                        "workspace.mode 重复出现"));
                continue;
            }
            modeSeen = true;
            Node valueNode = t.getValueNode();
            if (!(valueNode instanceof ScalarNode scalar) || !Tag.STR.equals(resolvedTag(scalar))) {
                issues.add(ValidationIssue.error("INVALID_TYPE", "workspace.mode",
                        absoluteLine(valueNode.getStartMark()),
                        "workspace.mode 必须是字符串"));
                continue;
            }
            String value = scalar.getValue();
            if ("shared".equals(value)) {
                if (source == SubagentDefinitionSource.USER) {
                    issues.add(ValidationIssue.error("WORKSPACE_MODE_NOT_ALLOWED", "workspace.mode",
                            absoluteLine(valueNode.getStartMark()),
                            "用户定义只允许 isolated 工作区"));
                    continue;
                }
                result = SubagentWorkspaceMode.SHARED;
            } else if ("isolated".equals(value)) {
                if (source == SubagentDefinitionSource.BUILTIN) {
                    issues.add(ValidationIssue.error("WORKSPACE_MODE_NOT_ALLOWED", "workspace.mode",
                            absoluteLine(valueNode.getStartMark()),
                            "内置定义必须使用 shared 工作区"));
                    continue;
                }
                result = SubagentWorkspaceMode.ISOLATED;
            } else {
                issues.add(ValidationIssue.error("INVALID_VALUE", "workspace.mode",
                        absoluteLine(valueNode.getStartMark()),
                        "workspace.mode 只允许 shared 或 isolated"));
            }
        }
        if (!modeSeen) {
            issues.add(ValidationIssue.error("MISSING_FIELD", "workspace.mode",
                    absoluteLine(tuple.valueNode().getStartMark()),
                    "声明 workspace 时必须提供 mode"));
        }
        return result;
    }

    // ---------- 工具方法 ----------

    /** plain scalar 的 tag 已由 Resolver 解析；quoted scalar 恒为 STR。 */
    private Tag resolvedTag(ScalarNode node) {
        Tag tag = node.getTag();
        if (tag == null) {
            return Tag.STR;
        }
        return tag;
    }

    private static Integer absoluteLine(Mark mark) {
        // front matter 从文档第 2 行开始；SnakeYAML mark 行号在 front matter 内 0 起始
        return mark == null ? null : mark.getLine() + 2;
    }

    private String safeParseMessage(MarkedYAMLException e) {
        String message = e.getProblem() == null ? e.getMessage() : e.getProblem();
        if (message == null) {
            return "语法错误";
        }
        return message.length() > 160 ? message.substring(0, 160) + "..." : message;
    }

    private static String stripBom(String text) {
        return text.startsWith("\uFEFF") ? text.substring(1) : text;
    }

    /** hash 规范化：统一换行为 LF 并去掉文末多余空白，保证幂等发布判定稳定。 */
    static String normalizeForHash(String text) {
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        return stripTrailing(normalized);
    }

    /** 对外暴露的规范化 hash 计算；synthetic 定义与幂等发布判定复用同一策略。 */
    public String contentHashOf(String markdown) {
        return sha256Hex(normalizeForHash(stripBom(markdown == null ? "" : markdown)));
    }

    private static String stripTrailing(String text) {
        int end = text.length();
        while (end > 0) {
            char c = text.charAt(end - 1);
            if (c == '\n' || c == ' ' || c == '\t') {
                end--;
            } else {
                break;
            }
        }
        return text.substring(0, end);
    }

    static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
