package com.h.backend.chat.interfaces.web;

import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.chat.domain.agent.AgentRuntimeType;
import com.h.backend.chat.domain.agent.AgentTopologyMapper;
import com.h.backend.chat.interfaces.dto.AgentTopologyDto;
import com.h.backend.common.api.ApiResponse;
import com.h.backend.common.exception.BusinessException;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.AgenticSystemTopology;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentControllerTest {

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
