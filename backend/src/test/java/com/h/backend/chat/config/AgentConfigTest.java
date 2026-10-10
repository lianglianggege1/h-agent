package com.h.backend.chat.infrastructure.config;

import com.h.backend.chat.infrastructure.ai.AgentConfig;
import com.h.backend.chat.infrastructure.ai.Agents;
import com.h.backend.chat.infrastructure.ai.decision.JevDecisionNodes;
import com.h.agent.observability.NoopAgentObservability;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.ChoiceAnswer;
import dev.langchain4j.model.decision.response.ScaleAnswer;
import dev.langchain4j.invocation.InvocationParameters;
import java.util.List;
import java.util.Map;
import com.h.backend.chat.infrastructure.ai.carrentalassistant.domain.CustomerInfo;
import com.h.backend.chat.infrastructure.ai.carrentalassistant.services.CarRentalAssistant;
import com.h.backend.chat.domain.agent.AgentStepListener;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.chat.infrastructure.memory.RedisChatMemoryStore;
import com.h.backend.memory.application.NoopLongTermMemoryRuntime;
import com.h.backend.memory.domain.AgentMemoryPolicyCatalog;
import com.h.backend.memory.infrastructure.langchain4j.ConversationContextAugmentorFactory;
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
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.any;

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
        ReflectionTestUtils.setField(config, "conversationContextAugmentorFactory",
                new ConversationContextAugmentorFactory(
                        new NoopLongTermMemoryRuntime(), new AgentMemoryPolicyCatalog()));

        CarRentalAssistant assistant = config.createAssistant();
        AgentInstance root = (AgentInstance) assistant;

        assertTrue(containsTopology(root, AgenticSystemTopology.ROUTER));
        assertTrue(containsTopology(root, AgenticSystemTopology.HUMAN_IN_THE_LOOP));
    }

    @Test
    void expertWorkflowUsesJevClassificationThenCallsOnlySelectedLlmExpert() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("Technical advice")).build());
        DecisionModel model = request -> DecisionResponse.builder()
                .answer(request.questions().keySet().iterator().next(),
                        ChoiceAnswer.builder().value("TECHNICAL").build()).build();
        AgentConfig config = decisionConfig(chatModel, model);

        var assistant = config.exportAssistant();
        var result = assistant.chat("expert-test", "Fix a Java error", new InvocationParameters());

        assertEquals("Technical advice", result.result());
        assertEquals(Agents.RequestCategory.TECHNICAL, result.agenticScope().readState("category"));
        verify(chatModel, times(1)).chat(any(ChatRequest.class));
        assertTrue(containsTopology((AgentInstance) assistant, AgenticSystemTopology.NON_AI_AGENT));
    }

    @Test
    void storyWorkflowUsesJevScoreAndKeepsEditedStoryAsOutput() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(
                        "{\"topic\":\"space\",\"style\":\"humorous\",\"audience\":\"adults\"}")).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("Draft")).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("Audience edit")).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("Style edit")).build());
        DecisionModel model = request -> {
            assertEquals(Map.of("story", "Style edit", "style", "humorous"), request.input());
            return DecisionResponse.builder().answer(request.questions().keySet().iterator().next(),
                    ScaleAnswer.builder().mean(3.6).build()).build();
        };
        var assistant = decisionConfig(chatModel, model).storyChat();

        var result = assistant.chat("1:2:story-test", "Write a humorous space story for adults", new InvocationParameters());

        assertEquals("Style edit", result.result());
        assertEquals(0.9, (Double) result.agenticScope().readState("score"), 1e-9);
        verify(chatModel, times(4)).chat(any(ChatRequest.class));
        assertTrue(containsTopology((AgentInstance) assistant, AgenticSystemTopology.NON_AI_AGENT));
    }

    private static AgentConfig decisionConfig(ChatModel chatModel, DecisionModel model) {
        AgentConfig config = new AgentConfig();
        RedisChatMemoryStore store = mock(RedisChatMemoryStore.class);
        when(store.getMessages(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(config, "chatModel", chatModel);
        ReflectionTestUtils.setField(config, "jevDecisionNodes", new JevDecisionNodes(model));
        ReflectionTestUtils.setField(config, "redisChatMemoryStore", store);
        ReflectionTestUtils.setField(config, "agentStepListener", mock(AgentStepListener.class));
        ReflectionTestUtils.setField(config, "agentObservability", NoopAgentObservability.getInstance());
        ReflectionTestUtils.setField(config, "chatMemoryIdFactory", new ChatMemoryIdFactory());
        ReflectionTestUtils.setField(config, "conversationContextAugmentorFactory",
                new ConversationContextAugmentorFactory(new NoopLongTermMemoryRuntime(), new AgentMemoryPolicyCatalog()));
        return config;
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
