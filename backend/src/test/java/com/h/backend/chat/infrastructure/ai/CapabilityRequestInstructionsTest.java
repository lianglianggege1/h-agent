package com.h.backend.chat.infrastructure.ai;

import com.h.backend.chat.application.capability.AgentPromptComposer;
import com.h.backend.chat.application.capability.CapabilityCatalog;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityRequestInstructionsTest {
    @Test
    void guidanceUsesActualRequestToolsWithoutChangingStoredRoleOrMessages() {
        String role = AgentPromptComposer.standardChat("你是翻译助手", "skill directory");
        var request = ChatRequest.builder().messages(SystemMessage.from(role), UserMessage.from("hello"))
                .toolSpecifications(ToolSpecification.builder().name("knowledge_search").description("search").build())
                .build();

        var transformed = CapabilityRequestInstructions.apply(request);
        String text = ((SystemMessage) transformed.messages().getFirst()).text();
        assertThat(text).contains("你是翻译助手", "skill directory", "knowledge_search")
                .doesNotContain("generate_video", "knowledge_save", "review_ocr", "memory_search");
        assertThat(((SystemMessage) request.messages().getFirst()).text()).isEqualTo(role);
        assertThat(transformed.messages().getLast()).isEqualTo(request.messages().getLast());
        assertThat(transformed.toolSpecifications()).isEqualTo(request.toolSpecifications());
    }

    @Test
    void leafMem0GuidanceDoesNotAdvertiseUnavailableNativeMemoryTools() {
        var tools = com.h.backend.memory.application.MemoryToolContract.harnessDefinitions().stream()
                .map(com.h.backend.memory.application.MemoryToolContract.Definition::name).toList();
        String text = CapabilityCatalog.instructions(CapabilityCatalog.Runtime.HARNESS, "memory", tools);
        assertThat(text).contains("mem0_operation", "不要求每轮先检索")
                .doesNotContain("memory_search", "memory_get", "memory_save");
        assertThat(CapabilityCatalog.instructions(CapabilityCatalog.Runtime.HARNESS, "memory", Set.of())).isEmpty();
    }

    @Test
    void completeDocumentGuidancePreservesOptionalReviewAndSingleDelivery() {
        var tools = CapabilityCatalog.forRuntime(CapabilityCatalog.Runtime.STANDARD_CHAT).stream()
                .filter(c -> c.id().equals("document")).findFirst().orElseThrow().tools();
        String text = CapabilityCatalog.instructions(CapabilityCatalog.Runtime.STANDARD_CHAT, "document", tools);
        assertThat(text).contains("RECOGNIZED", "仅在用户明确要求", "review_ocr", "不得为再次发送")
                .doesNotContain("禁止通过Shell、文件系统或生成工具绕过识别复核");
    }

    @Test
    void requestsWithNoToolsDoNotAdvertiseCapabilities() {
        var request = ChatRequest.builder().messages(List.of(UserMessage.from("hello"))).build();
        assertThat(CapabilityRequestInstructions.apply(request)).isSameAs(request);
    }
}
