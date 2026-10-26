package com.h.backend.chat.interfaces.web;

import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.chat.domain.agent.AgentRuntimeType;
import com.h.backend.chat.domain.agent.AgentTopologyMapper;
import com.h.backend.chat.infrastructure.ai.experts.ExpertAssistantWorkflow;
import com.h.backend.chat.interfaces.dto.AgentTopologyDto;
import com.h.backend.common.api.ApiResponse;
import com.h.backend.common.exception.BusinessException;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.AgenticSystemTopology;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentControllerTest {

    @ParameterizedTest
    @ValueSource(doubles = {0.6, 0.75})
    void topologyMapsRealExpertAssistantDecisionRouter(double threshold) {
        ChatModel chatModel = mock(ChatModel.class);
        Object[] experts = List.of("医疗专家", "法律专家", "技术专家").stream()
                .map(name -> AgenticServices.nonAiAgentBuilder(scope -> "answer")
                        .name(name).description(name + "领域的问题")
                        .inputKey(String.class, "request").outputKey(name + "Response")
                        .outputType(String.class).build())
                .toArray();
        var assistant = ExpertAssistantWorkflow.build(null, chatModel, new AgentListener() {}, threshold, experts);
        AgentDefinition definition = new AgentDefinition(
                "export-assistant", "专家助手", "通用", List.of(), "summary", assistant,
                AgentRuntimeType.AGENTIC_SYNC, true
        );
        AgentController controller = new AgentController(
                new AgentRegistry(List.of(definition)), new AgentTopologyMapper()
        );

        ApiResponse<AgentTopologyDto> response = controller.topology("export-assistant");

        assertEquals("export-assistant", response.data().agent().agentId());
        assertEquals("SEQUENCE", response.data().root().topology());
        assertEquals(2, response.data().root().children().size());
        var router = response.data().root().children().getFirst();
        assertEquals("ROUTER", router.topology());
        assertNotNull(router.decisionRouter());
        assertEquals("MULTIPLE", router.decisionRouter().selectionMode());
        assertEquals(threshold, router.decisionRouter().activationThreshold());
        assertEquals("PARALLEL", router.decisionRouter().dispatchMode());
        assertEquals(List.of("P(Yes) >= " + threshold, "P(Yes) >= " + threshold, "P(Yes) >= " + threshold),
                router.children().stream().map(child -> child.condition()).toList());
        assertEquals(List.of("医疗专家", "法律专家", "技术专家"), router.children().stream()
                .map(child -> child.name()).toList());
    }

    @Test
    void topologyRejectsEnabledNonAgentInstance() {
        AgentDefinition standardChat = new AgentDefinition(
                AgentRegistry.STANDARD_CHAT_AGENT_ID,
                "普通聊天",
                "通用",
                List.of("聊天"),
                "summary",
                new Object(),
                AgentRuntimeType.STANDARD_STREAMING_CHAT,
                true
        );
        AgentController controller = new AgentController(
                new AgentRegistry(List.of(standardChat)),
                new AgentTopologyMapper()
        );

        BusinessException exception = assertThrows(
                BusinessException.class,
                () -> controller.topology(AgentRegistry.STANDARD_CHAT_AGENT_ID)
        );

        assertEquals(41002, exception.getCode());
        assertEquals("该 Agent 暂不支持编排拓扑", exception.getMessage());
    }

    @Test
    void topologyMapsAgentInstance() {
        AgentInstance agent = mock(AgentInstance.class);
        when(agent.agentId()).thenReturn("car");
        when(agent.name()).thenReturn("Car");
        when(agent.topology()).thenReturn(AgenticSystemTopology.AI_AGENT);
        doReturn(String.class).when(agent).type();
        when(agent.description()).thenReturn("Car desc");
        when(agent.outputType()).thenReturn(String.class);
        when(agent.outputKey()).thenReturn("response");
        when(agent.async()).thenReturn(false);
        when(agent.arguments()).thenReturn(List.of());
        when(agent.subagents()).thenReturn(List.of());

        AgentDefinition car = new AgentDefinition(
                "car",
                "Car Agent",
                "出行",
                List.of("rental"),
                "summary",
                agent,
                AgentRuntimeType.AGENTIC_SYNC,
                true
        );
        AgentController controller = new AgentController(
                new AgentRegistry(List.of(car)),
                new AgentTopologyMapper()
        );

        ApiResponse<AgentTopologyDto> response = controller.topology("car");

        assertNotNull(response.data());
        assertEquals("car", response.data().agent().agentId());
        assertEquals("AI_AGENT", response.data().root().topology());
    }
}
