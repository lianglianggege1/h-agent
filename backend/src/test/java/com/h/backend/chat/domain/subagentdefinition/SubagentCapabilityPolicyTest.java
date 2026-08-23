package com.h.backend.chat.domain.subagentdefinition;

import com.h.backend.chat.domain.subagentdefinition.model.CapabilityDeclaration;
import com.h.backend.chat.domain.subagentdefinition.model.CompiledSubagentDefinition;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentRuntimeKind;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentWorkspaceMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class SubagentCapabilityPolicyTest {

    private final SubagentCapabilityPolicy policy = new SubagentCapabilityPolicy();

    private CompiledSubagentDefinition compiled(CapabilityDeclaration tools) {
        return new CompiledSubagentDefinition(
                "名称", "描述", "subagent", "inherit", 10,
                tools, CapabilityDeclaration.OMITTED,
                SubagentWorkspaceMode.ISOLATED, SubagentRuntimeKind.CATALOG_DECLARATION,
                "system prompt");
    }

    @Test
    void omittedToolsResolveToPlatformDefaultReadOnlySet() {
        var effective = policy.effective(compiled(CapabilityDeclaration.OMITTED), null);

        assertThat(effective.tools()).containsExactly(
                "read_file", "grep_files", "glob_files", "list_files");
        assertThat(effective.skills()).isEmpty();
    }

    @Test
    void emptyToolsResolveToNoCapabilityNotInheritAll() {
        var effective = policy.effective(compiled(CapabilityDeclaration.empty()), null);

        assertThat(effective.tools()).isEmpty();
    }

    @Test
    void explicitToolsIntersectWithPlatformAllowedSet() {
        var effective = policy.effective(
                compiled(CapabilityDeclaration.explicit(
                        List.of("read_file", "write_file", "agent_spawn", "shell"))),
                null);

        // agent_spawn / shell 始终被平台排除，即使写进声明。
        assertThat(effective.tools()).containsExactlyInAnyOrder("read_file", "write_file");
    }

    @Test
    void parentDenySubtractsFromEffectiveTools() {
        var effective = policy.effective(
                compiled(CapabilityDeclaration.explicit(
                        List.of("read_file", "write_file", "edit_file"))),
                Set.of("write_file"));

        assertThat(effective.tools()).containsExactlyInAnyOrder("read_file", "edit_file");
    }

    @Test
    void omittedSkillsResolveToEmptyAndExplicitSkillsAlwaysEmptyInPhase1() {
        var explicitSkills = policy.effective(
                new CompiledSubagentDefinition(
                        "名称", "描述", "subagent", "inherit", 10,
                        CapabilityDeclaration.OMITTED,
                        CapabilityDeclaration.explicit(List.of("any-skill")),
                        SubagentWorkspaceMode.ISOLATED,
                        SubagentRuntimeKind.CATALOG_DECLARATION,
                        "system prompt"),
                null);

        // 第一期平台未开放 Skill Catalog：显式技能在编译期已被拒绝，
        // 运行期交集兜底同样为空。
        assertThat(explicitSkills.skills()).isEmpty();
    }

    @Test
    void policyRevisionIsMonotonicPositive() {
        assertThat(policy.policyRevision()).isPositive();
    }

    @Test
    void allowedToolsContainDefaultAndRequestableOnly() {
        var tools = policy.allowedTools();

        assertThat(tools).containsExactlyInAnyOrder(
                "read_file", "grep_files", "glob_files", "list_files", "write_file", "edit_file");
    }
}
