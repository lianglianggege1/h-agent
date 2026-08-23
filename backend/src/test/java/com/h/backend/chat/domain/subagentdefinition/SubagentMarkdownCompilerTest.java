package com.h.backend.chat.domain.subagentdefinition;

import com.h.backend.chat.domain.subagentdefinition.model.CapabilityDeclaration;
import com.h.backend.chat.domain.subagentdefinition.model.CompileOutcome;
import com.h.backend.chat.domain.subagentdefinition.model.CompiledSubagentDefinition;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionSource;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentRuntimeKind;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentWorkspaceMode;
import com.h.backend.chat.domain.subagentdefinition.model.ValidationIssue;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentMarkdownCompilerTest {

    private final SubagentMarkdownCompiler compiler = new SubagentMarkdownCompiler();
    private final Set<String> allowedTools = Set.of(
            "read_file", "grep_files", "glob_files", "list_files", "write_file", "edit_file");

    private static final String VALID_USER = """
            ---
            display_name: 我的资料整理员
            description: 阅读当前任务提供的资料并整理带出处的结论
            mode: subagent
            model: inherit
            steps: 10
            tools: [read_file, grep_files, glob_files, list_files]
            skills: []
            workspace:
              mode: isolated
            ---

            你是一名资料整理 Subagent。

            围绕父 Agent 的委托工作，不扩展任务范围；结论与证据分开陈述。
            """;

    private static final String VALID_BUILTIN = """
            ---
            display_name: 代码审查员
            description: 审查代码中的正确性、安全性和可维护性问题
            mode: subagent
            model: inherit
            steps: 8
            tools: [read_file, grep_files, glob_files, list_files]
            skills: []
            workspace:
              mode: shared
            ---

            你是一名代码审查 Subagent。
            """;

    @Test
    void shouldCompileValidUserDefinition() {
        CompileOutcome outcome = compiler.compile(
                VALID_USER, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.hasErrors()).as("issues: %s", outcome.issues()).isFalse();
        CompiledSubagentDefinition compiled = outcome.compiled();
        assertThat(compiled.displayName()).isEqualTo("我的资料整理员");
        assertThat(compiled.mode()).isEqualTo(CompiledSubagentDefinition.MODE_SUBAGENT);
        assertThat(compiled.model()).isEqualTo(CompiledSubagentDefinition.MODEL_INHERIT);
        assertThat(compiled.steps()).isEqualTo(10);
        assertThat(compiled.tools().kind()).isEqualTo(CapabilityDeclaration.Kind.EXPLICIT);
        assertThat(compiled.tools().names()).containsExactly(
                "read_file", "grep_files", "glob_files", "list_files");
        assertThat(compiled.skills().kind()).isEqualTo(CapabilityDeclaration.Kind.EMPTY);
        assertThat(compiled.workspaceMode()).isEqualTo(SubagentWorkspaceMode.ISOLATED);
        assertThat(compiled.runtimeKind()).isEqualTo(SubagentRuntimeKind.CATALOG_DECLARATION);
        assertThat(compiled.systemPrompt()).startsWith("你是一名资料整理 Subagent。");
        assertThat(outcome.contentHash()).hasSize(64);
    }

    @Test
    void shouldCompileValidBuiltinDefinition() {
        CompileOutcome outcome = compiler.compile(
                VALID_BUILTIN, SubagentDefinitionSource.BUILTIN, allowedTools, Set.of());

        assertThat(outcome.hasErrors()).isFalse();
        assertThat(outcome.compiled().workspaceMode()).isEqualTo(SubagentWorkspaceMode.SHARED);
    }

    @Test
    void shouldRejectUserSharedWorkspace() {
        String markdown = VALID_USER.replace("mode: isolated", "mode: shared");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.hasErrors()).isTrue();
        assertThat(outcome.compiled()).isNull();
        assertThat(outcome.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo("WORKSPACE_MODE_NOT_ALLOWED");
                    assertThat(issue.field()).isEqualTo("workspace.mode");
                    assertThat(issue.line()).isEqualTo(10);
                });
    }

    @Test
    void shouldRejectBuiltinMissingSharedWorkspace() {
        String markdown = """
                ---
                display_name: 研究员
                description: 搜集事实
                ---

                你是研究员。
                """;
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.BUILTIN, allowedTools, Set.of());

        assertThat(outcome.hasErrors()).isTrue();
        assertThat(outcome.issues())
                .anyMatch(issue -> "MISSING_FIELD".equals(issue.code())
                        && "workspace.mode".equals(issue.field()));
    }

    @Test
    void shouldRejectUnknownFieldWithLine() {
        String markdown = VALID_USER.replace("steps: 10", "steps: 10\ntemperature: 0.7");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.hasErrors()).isTrue();
        assertThat(outcome.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo("UNKNOWN_FIELD");
                    assertThat(issue.field()).isEqualTo("temperature");
                    assertThat(issue.line()).isEqualTo(7);
                    assertThat(issue.column()).isNotNull();
                });
    }

    @Test
    void shouldRejectHighRiskFields() {
        for (String field : List.of("mcp: {}", "persistSession: true", "url: http://x",
                "headers: {}", "expose_to_user: true", "inheritParentPermissions: true")) {
            String markdown = VALID_USER.replace("steps: 10", "steps: 10\n" + field);
            CompileOutcome outcome = compiler.compile(
                    markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());
            assertThat(outcome.hasErrors()).as("field %s should be rejected", field).isTrue();
            assertThat(outcome.issues())
                    .anyMatch(issue -> "UNKNOWN_FIELD".equals(issue.code()));
        }
    }

    @Test
    void shouldRejectWorkspacePathSubField() {
        String markdown = VALID_USER.replace("  mode: isolated", "  mode: isolated\n  path: /tmp/x");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anyMatch(issue -> "UNKNOWN_FIELD".equals(issue.code())
                        && "workspace.path".equals(issue.field()));
    }

    @Test
    void shouldReportMissingRequiredFields() {
        String markdown = """
                ---
                steps: 5
                ---

                正文。
                """;
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo("MISSING_FIELD");
                    assertThat(issue.field()).isEqualTo("display_name");
                })
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo("MISSING_FIELD");
                    assertThat(issue.field()).isEqualTo("description");
                });
    }

    @Test
    void shouldRejectNonInheritModel() {
        String markdown = VALID_USER.replace("model: inherit", "model: gpt-4");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anyMatch(issue -> "INVALID_VALUE".equals(issue.code())
                        && "model".equals(issue.field()));
    }

    @Test
    void shouldRejectInvalidMode() {
        String markdown = VALID_USER.replace("mode: subagent", "mode: supervisor");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anyMatch(issue -> "INVALID_VALUE".equals(issue.code())
                        && "mode".equals(issue.field()));
    }

    @Test
    void shouldRejectStepsOutOfRange() {
        String markdown = VALID_USER.replace("steps: 10", "steps: 21");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anyMatch(issue -> "INVALID_VALUE".equals(issue.code())
                        && "steps".equals(issue.field()));
    }

    @Test
    void shouldRejectNonIntegerSteps() {
        String markdown = VALID_USER.replace("steps: 10", "steps: \"10\"");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anyMatch(issue -> "INVALID_TYPE".equals(issue.code())
                        && "steps".equals(issue.field()));
    }

    @Test
    void shouldDefaultStepsWhenOmitted() {
        String markdown = VALID_USER.replace("steps: 10\n", "");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.hasErrors()).isFalse();
        assertThat(outcome.compiled().steps()).isEqualTo(10);
    }

    @Test
    void shouldApplyOmittedToolsDefaultSemantics() {
        String markdown = VALID_USER.replace("tools: [read_file, grep_files, glob_files, list_files]\n", "");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.hasErrors()).isFalse();
        assertThat(outcome.compiled().tools().kind()).isEqualTo(CapabilityDeclaration.Kind.OMITTED);
    }

    @Test
    void shouldApplyExplicitEmptyToolsSemantics() {
        String markdown = VALID_USER.replace(
                "tools: [read_file, grep_files, glob_files, list_files]", "tools: []");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.hasErrors()).isFalse();
        assertThat(outcome.compiled().tools().kind()).isEqualTo(CapabilityDeclaration.Kind.EMPTY);
    }

    @Test
    void shouldRejectDisallowedToolWithIndexAndLine() {
        String markdown = VALID_USER.replace(
                "tools: [read_file, grep_files, glob_files, list_files]",
                "tools: [read_file, agent_spawn]");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo("TOOL_NOT_ALLOWED");
                    assertThat(issue.field()).isEqualTo("tools[1]");
                    assertThat(issue.line()).isEqualTo(7);
                });
    }

    @Test
    void shouldRejectInaccessibleSkill() {
        String markdown = VALID_USER.replace("skills: []", "skills: [super-skill]");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anyMatch(issue -> "SKILL_NOT_ACCESSIBLE".equals(issue.code())
                        && "skills[0]".equals(issue.field()));
    }

    @Test
    void shouldReportDuplicateEntriesAsWarning() {
        String markdown = VALID_USER.replace(
                "tools: [read_file, grep_files, glob_files, list_files]",
                "tools: [read_file, read_file]");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.hasErrors()).isFalse();
        assertThat(outcome.issues())
                .anyMatch(issue -> issue.severity() == ValidationIssue.Severity.WARNING
                        && "DUPLICATE_ENTRY".equals(issue.code()));
        assertThat(outcome.compiled().tools().names()).containsExactly("read_file");
    }

    @Test
    void shouldRejectEmptyBody() {
        String markdown = """
                ---
                display_name: 名称
                description: 描述
                workspace:
                  mode: isolated
                ---

                """;
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anyMatch(issue -> "EMPTY_BODY".equals(issue.code()));
    }

    @Test
    void shouldRejectMissingFrontMatter() {
        CompileOutcome outcome = compiler.compile(
                "只有正文，没有 front matter。", SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anyMatch(issue -> "FRONT_MATTER_MISSING".equals(issue.code()));
    }

    @Test
    void shouldRejectUnclosedFrontMatter() {
        String markdown = VALID_USER.replaceFirst("\n---\n\n", "\n\n");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anyMatch(issue -> "FRONT_MATTER_UNCLOSED".equals(issue.code()));
    }

    @Test
    void shouldRejectBrokenYamlWithLine() {
        String markdown = VALID_USER.replace("steps: 10", "steps: [10");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anySatisfy(issue -> {
                    assertThat(issue.code()).isEqualTo("YAML_PARSE_ERROR");
                    assertThat(issue.line()).isNotNull();
                });
    }

    @Test
    void shouldRejectOversizedMarkdown() {
        String bigBody = "x".repeat(33 * 1024);
        String markdown = "---\ndisplay_name: 名称\ndescription: 描述\n---\n\n" + bigBody;
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anyMatch(issue -> "SIZE_EXCEEDED".equals(issue.code()));
    }

    @Test
    void shouldRejectOverlongDisplayName() {
        String markdown = VALID_USER.replace("我的资料整理员", "名".repeat(81));
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anyMatch(issue -> "LENGTH_EXCEEDED".equals(issue.code())
                        && "display_name".equals(issue.field()));
    }

    @Test
    void shouldRejectOverlongDescription() {
        String markdown = VALID_USER.replace(
                "阅读当前任务提供的资料并整理带出处的结论", "述".repeat(501));
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anyMatch(issue -> "LENGTH_EXCEEDED".equals(issue.code())
                        && "description".equals(issue.field()));
    }

    @Test
    void shouldRejectDuplicateFields() {
        String markdown = VALID_USER.replace("steps: 10", "steps: 10\nsteps: 12");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anyMatch(issue -> "DUPLICATE_FIELD".equals(issue.code())
                        && "steps".equals(issue.field()));
    }

    @Test
    void shouldProduceStableHashAcrossLineEndingNormalization() {
        String crlf = VALID_USER.replace("\n", "\r\n");
        String trailing = VALID_USER + "\n\n\n";

        CompileOutcome a = compiler.compile(
                VALID_USER, SubagentDefinitionSource.USER, allowedTools, Set.of());
        CompileOutcome b = compiler.compile(
                crlf, SubagentDefinitionSource.USER, allowedTools, Set.of());
        CompileOutcome c = compiler.compile(
                trailing, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(b.contentHash()).isEqualTo(a.contentHash());
        assertThat(c.contentHash()).isEqualTo(a.contentHash());
    }

    @Test
    void shouldDefaultUserWorkspaceToIsolatedWhenOmitted() {
        String markdown = VALID_USER.replace("workspace:\n  mode: isolated\n", "");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.hasErrors()).isFalse();
        assertThat(outcome.compiled().workspaceMode()).isEqualTo(SubagentWorkspaceMode.ISOLATED);
    }

    @Test
    void shouldRejectNonStringDisplayName() {
        String markdown = VALID_USER.replace("display_name: 我的资料整理员", "display_name: 12345");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anyMatch(issue -> "INVALID_TYPE".equals(issue.code())
                        && "display_name".equals(issue.field()));
    }

    @Test
    void shouldRejectNonListTools() {
        String markdown = VALID_USER.replace(
                "tools: [read_file, grep_files, glob_files, list_files]", "tools: read_file");
        CompileOutcome outcome = compiler.compile(
                markdown, SubagentDefinitionSource.USER, allowedTools, Set.of());

        assertThat(outcome.issues())
                .anyMatch(issue -> "INVALID_TYPE".equals(issue.code())
                        && "tools".equals(issue.field()));
    }
}
