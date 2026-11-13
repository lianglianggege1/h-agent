package com.h.backend.chat.infrastructure.ai;

import com.h.agent.observability.NoopAgentObservability;
import com.h.agent.observability.langchain4j.ObservingToolProvider;
import com.h.backend.chat.application.capability.CapabilityCatalog;
import com.h.backend.skill.application.SkillRuntimeService;
import com.h.backend.skill.application.SkillRuntimeToolProvider;
import dev.langchain4j.agent.tool.ToolSpecifications;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class CapabilityCatalogTest {
    @Test
    void standardCatalogCoversTheActualStaticAdapterTools() throws Exception {
        var types = java.util.List.of(
                com.h.backend.generation.interfaces.tool.VideoGenerationTool.class,
                com.h.backend.document.interfaces.tool.LangChain4jDocumentTool.class,
                com.h.backend.automation.interfaces.tool.LangChain4jAutomationTool.class,
                com.h.backend.chat.infrastructure.tools.ImageGenerationTool.class,
                com.h.backend.chat.infrastructure.tools.FilesystemTool.class,
                com.h.backend.chat.infrastructure.tools.FileDeliveryTool.class,
                com.h.backend.chat.infrastructure.tools.ShellTool.class,
                com.h.backend.chat.infrastructure.tools.WebSearchTool.class);
        var actual = new java.util.HashSet<String>();
        for (Class<?> type : types) {
            var constructor = type.getConstructors()[0];
            Object instance = constructor.newInstance(Arrays.stream(constructor.getParameterTypes())
                    .map(org.mockito.Mockito::mock).toArray());
            ToolSpecifications.toolSpecificationsFrom(instance).forEach(spec -> actual.add(spec.name()));
        }
        Set<String> names = CapabilityCatalog.toolNames(CapabilityCatalog.Runtime.STANDARD_CHAT);
        assertThat(actual).hasSize(24);
        assertThat(names).containsAll(actual).hasSize(40);
    }

    @Test
    void harnessUsesMem0NamespaceAndDoesNotClaimUnsupportedImageOrWebTools() {
        var names = CapabilityCatalog.toolNames(CapabilityCatalog.Runtime.HARNESS);
        assertThat(names).contains("mem0_search", "mem0_save", "knowledge_save")
                .doesNotContain("memory_search", "generateImage", "web_search");
        assertThat(CapabilityCatalog.forRuntime(CapabilityCatalog.Runtime.HARNESS).stream()
                .map(CapabilityCatalog.Capability::id).collect(Collectors.toSet()))
                .doesNotContain("image", "web", "shell");
    }

    @Test
    void originalSkillProviderKeepsDynamicBehaviorThroughObservation() {
        var provider = new SkillRuntimeToolProvider(mock(SkillRuntimeService.class));
        assertThat(new ObservingToolProvider(provider, NoopAgentObservability.getInstance(), "langchain4j")
                .isDynamic()).isTrue();
    }
}
