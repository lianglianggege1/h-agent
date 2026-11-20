package com.h.backend.chat.ai.a2a;

import com.h.agent.observability.AgentObservability;
import com.h.backend.chat.domain.agent.AgentStepListener;
import com.h.capabilities.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.chat.infrastructure.ai.a2a.A2AAgentConfig;
import com.h.backend.chat.infrastructure.ai.a2a.A2ARemoteAgents;
import com.h.backend.chat.infrastructure.ai.a2a.NacosA2AClientFactory;
import com.h.backend.chat.infrastructure.memory.RedisChatMemoryStore;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.internal.A2AClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.*;

class A2AAgentConfigTest {
    @Test
    void buildsEntireStoryWorkflowWithDiscoveredRemoteAgents() {
        var model = mock(ChatModel.class);
        var factory = mock(NacosA2AClientFactory.class);
        remote(factory, model, "creative-writer", A2ARemoteAgents.CreativeWriter.class);
        remote(factory, model, "audience-editor", A2ARemoteAgents.AudienceEditor.class);
        remote(factory, model, "style-editor", A2ARemoteAgents.StyleEditor.class);
        var config = new A2AAgentConfig();
        ReflectionTestUtils.setField(config, "chatModel", model);
        ReflectionTestUtils.setField(config, "nacosA2AClientFactory", factory);
        ReflectionTestUtils.setField(config, "redisChatMemoryStore", mock(RedisChatMemoryStore.class));
        ReflectionTestUtils.setField(config, "chatMemoryIdFactory", mock(ChatMemoryIdFactory.class));
        ReflectionTestUtils.setField(config, "agentStepListener", mock(AgentStepListener.class));
        ReflectionTestUtils.setField(config, "agentObservability", mock(AgentObservability.class));
        try {
            assertNotNull(config.a2aStoryAssistant());
        } finally {
            com.h.backend.observability.a2a.ObservingA2AHttpClientProvider.initialize(null);
        }
        verifyNoInteractions(model);
    }

    @SuppressWarnings("unchecked")
    private <T> void remote(NacosA2AClientFactory factory, ChatModel model, String name, Class<T> type) {
        A2AClientBuilder<T> builder = mock(A2AClientBuilder.class, RETURNS_SELF);
        when(factory.builder(name, type)).thenReturn(builder);
        when(builder.build()).thenReturn(AgenticServices.agentBuilder(type).chatModel(model).build());
    }
}
