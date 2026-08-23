package com.h.backend.chat.infrastructure.subagent;

import com.h.backend.chat.domain.subagentdefinition.SubagentCapabilityPolicy;
import com.h.backend.chat.domain.subagentdefinition.SubagentMarkdownCompiler;
import io.agentscope.harness.agent.subagent.SubagentDeclaration;
import io.agentscope.harness.agent.subagent.WorkspaceMode;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltinSubagentDeclarationsTest {

    private final BuiltinSubagentDeclarations declarations = new BuiltinSubagentDeclarations(
            new ClasspathBuiltinDefinitionAdapter(
                    new SubagentMarkdownCompiler(), new SubagentCapabilityPolicy()),
            new SubagentCapabilityPolicy());

    @Test
    void shouldRegisterCodebaseBuiltinsButNotSyntheticGeneralPurpose() {
        List<SubagentDeclaration> result = declarations.declarations();

        assertThat(result)
                .extracting(SubagentDeclaration::getName)
                .containsExactly("planner", "researcher", "reviewer");
    }

    @Test
    void shouldMapCompiledDefinitionToLeafSharedDeclaration() {
        SubagentDeclaration researcher = declarations.declarations().stream()
                .filter(decl -> "researcher".equals(decl.getName()))
                .findFirst().orElseThrow();

        assertThat(researcher.getDescription())
                .isEqualTo("搜集和核对事实，区分证据与推断，产出带出处的结论");
        assertThat(researcher.getSteps()).isEqualTo(12);
        assertThat(researcher.getInlineAgentsBody()).isNotBlank();
        assertThat(researcher.getWorkspaceMode()).isEqualTo(WorkspaceMode.SHARED);
        assertThat(researcher.getMode()).isEqualTo(SubagentDeclaration.Mode.SUBAGENT);
        assertThat(researcher.isPersistSession()).isFalse();
        assertThat(researcher.hasDefinitionWorkspace()).isFalse();
        assertThat(researcher.isRemote()).isFalse();
        // SDK 语义：非空 tools 表示 allowlist 过滤继承工具。
        assertThat(researcher.getTools())
                .containsExactly("read_file", "grep_files", "glob_files", "list_files");
    }

    @Test
    void shouldInheritParentModelByDefault() {
        for (SubagentDeclaration declaration : declarations.declarations()) {
            assertThat(declaration.getModel()).isBlank();
        }
    }
}
