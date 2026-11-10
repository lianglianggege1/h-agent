package com.h.backend.chat.infrastructure.subagent;

import com.h.backend.chat.domain.subagentdefinition.SubagentCapabilityPolicy;
import com.h.backend.chat.domain.subagentdefinition.SubagentMarkdownCompiler;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentRuntimeKind;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentWorkspaceMode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ClasspathBuiltinDefinitionAdapterTest {

    private final ClasspathBuiltinDefinitionAdapter adapter =
            new ClasspathBuiltinDefinitionAdapter(
                    new SubagentMarkdownCompiler(), new SubagentCapabilityPolicy());

    @Test
    void shouldLoadCodebaseBuiltinsPlusSyntheticGeneralPurpose() {
        List<ClasspathBuiltinDefinitionAdapter.BuiltinDefinition> definitions = adapter.load();

        assertThat(definitions)
                .extracting(ClasspathBuiltinDefinitionAdapter.BuiltinDefinition::agentId)
                .containsExactly(
                        "general-purpose", "planner", "researcher", "reviewer");
    }

    @Test
    void shouldMarkGeneralPurposeAsSyntheticSdkRuntime() {
        var generalPurpose = adapter.load().stream()
                .filter(def -> "general-purpose".equals(def.agentId()))
                .findFirst().orElseThrow();

        assertThat(generalPurpose.synthetic()).isTrue();
        assertThat(generalPurpose.compiled().runtimeKind())
                .isEqualTo(SubagentRuntimeKind.SDK_GENERAL_PURPOSE);
        assertThat(generalPurpose.compiled().workspaceMode())
                .isEqualTo(SubagentWorkspaceMode.SHARED);
        assertThat(generalPurpose.contentHash()).hasSize(64);
    }

    @Test
    void shouldCompileCodebaseBuiltinsAsCatalogDeclarations() {
        var researcher = adapter.load().stream()
                .filter(def -> "researcher".equals(def.agentId()))
                .findFirst().orElseThrow();

        assertThat(researcher.synthetic()).isFalse();
        assertThat(researcher.compiled().runtimeKind())
                .isEqualTo(SubagentRuntimeKind.CATALOG_DECLARATION);
        assertThat(researcher.compiled().workspaceMode())
                .isEqualTo(SubagentWorkspaceMode.SHARED);
        assertThat(researcher.compiled().displayName()).isEqualTo("资料研究员");
        assertThat(researcher.compiled().tools().names())
                .containsExactly("read_file", "grep_files", "glob_files", "list_files",
                "mem0_search", "mem0_get", "mem0_save", "mem0_update", "mem0_supersede", "mem0_set_status", "mem0_history", "mem0_operation", "mem0_read_source",
                "knowledge_list", "knowledge_search", "knowledge_read", "knowledge_save", "knowledge_update", "knowledge_history", "knowledge_operation");
        assertThat(researcher.markdown()).contains("---");
    }

    @Test
    void releaseIdIsStableAndDerivedFromContent() {
        List<ClasspathBuiltinDefinitionAdapter.BuiltinDefinition> definitions = adapter.load();

        String releaseId = adapter.releaseId(definitions);
        String again = adapter.releaseId(adapter.load());

        assertThat(releaseId).hasSize(32);
        assertThat(again).isEqualTo(releaseId);
    }

    @Test
    void reservedAgentIdsCoverAllBuiltins() {
        Set<String> reserved = adapter.reservedAgentIds(adapter.load());

        assertThat(reserved).containsExactlyInAnyOrder(
                "general-purpose", "planner", "researcher", "reviewer");
    }
}
