package com.h.backend.chat.config;

import com.h.backend.chat.ai.AgentConfig;
import com.h.backend.chat.ai.carrentalassistant.domain.CustomerInfo;
import com.h.backend.chat.ai.carrentalassistant.services.CarRentalAssistant;
import com.h.backend.chat.agent.AgentStepListener;
import com.h.backend.chat.memory.ChatMemoryIdFactory;
import com.h.backend.chat.memory.RedisChatMemoryStore;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.AgenticSystemTopology;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class AgentConfigTest {

    @Test
    void buildsClarificationForMissingCustomerInfo() {
        CustomerInfo customerInfo = new CustomerInfo();
        customerInfo.setName("张三");
        customerInfo.setCarMake("Toyota");

        String clarification = AgentConfig.customerInfoClarification(customerInfo);

        assertEquals("为了继续处理租车救援请求，请补充：预订参考号或客户编号、车辆型号、当前位置。", clarification);
    }

    @Test
    void usesGeneralClarificationWhenCustomerInfoIsMissing() {
        String clarification = AgentConfig.customerInfoClarification(null);

        assertEquals("为了继续处理租车救援请求，请补充：客户姓名、预订参考号或客户编号、车辆品牌、车辆型号、当前位置。", clarification);
    }

    @Test
    void carRentalAssistantRoutesIncompleteCustomerInfoToHumanInTheLoop() {
        AgentConfig config = new AgentConfig();
        ReflectionTestUtils.setField(config, "chatModel", mock(ChatModel.class));
        ReflectionTestUtils.setField(config, "redisChatMemoryStore", mock(RedisChatMemoryStore.class));
        ReflectionTestUtils.setField(config, "agentStepListener", mock(AgentStepListener.class));
        ReflectionTestUtils.setField(config, "chatMemoryIdFactory", new ChatMemoryIdFactory());

        CarRentalAssistant assistant = config.createAssistant();
        AgentInstance root = (AgentInstance) assistant;

        assertTrue(containsTopology(root, AgenticSystemTopology.ROUTER));
        assertTrue(containsTopology(root, AgenticSystemTopology.HUMAN_IN_THE_LOOP));
    }

    private static boolean containsTopology(AgentInstance root, AgenticSystemTopology topology) {
        Queue<AgentInstance> queue = new ArrayDeque<>();
        queue.add(root);
        while (!queue.isEmpty()) {
            AgentInstance current = queue.remove();
            if (current.topology() == topology) {
                return true;
            }
            if (current.subagents() != null) {
                queue.addAll(current.subagents());
            }
        }
        return false;
    }
}
