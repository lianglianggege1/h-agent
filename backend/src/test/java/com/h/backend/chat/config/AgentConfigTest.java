package com.h.backend.chat.infrastructure.config;

import com.h.backend.chat.infrastructure.ai.AgentConfig;
import com.h.backend.chat.infrastructure.ai.decision.JevDecisionNodes;
import com.h.agent.observability.NoopAgentObservability;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.ScaleAnswer;
import dev.langchain4j.model.decision.response.YesNoAnswer;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import java.util.List;
import java.util.Map;
import com.h.backend.chat.infrastructure.ai.carrentalassistant.domain.CustomerInfo;
import com.h.backend.chat.infrastructure.ai.carrentalassistant.services.CarRentalAssistant;
import com.h.backend.chat.domain.agent.AgentStepListener;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.chat.infrastructure.memory.RedisChatMemoryStore;
import com.h.backend.memory.application.NoopLongTermMemoryRuntime;
import com.h.backend.memory.application.LongTermMemoryRuntime;
import com.h.backend.memory.domain.MemoryInvocationContext;
import com.h.backend.memory.domain.MemoryRecallCommand;
import com.h.backend.memory.domain.MemoryRecallResult;
import org.mockito.ArgumentCaptor;
import com.h.backend.memory.domain.AgentMemoryPolicyCatalog;
import com.h.backend.memory.infrastructure.langchain4j.ConversationContextAugmentorFactory;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.AgenticSystemTopology;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

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
    void expertWorkflowRunsRelevantExpertsInParallelThenSynthesizes() {
        ChatModel chatModel = mock(ChatModel.class);
        CountDownLatch expertsStarted = new CountDownLatch(2);
        when(chatModel.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            String prompt = invocation.<ChatRequest>getArgument(0).messages().toString();
            String answer;
            if (prompt.contains("综合专家回答")) {
                assertEquals(0, expertsStarted.getCount());
                assertTrue(prompt.contains("Technical advice"));
                assertTrue(prompt.contains("Legal advice"));
                answer = "Combined advice";
            } else {
                expertsStarted.countDown();
                assertTrue(expertsStarted.await(3, TimeUnit.SECONDS), "Experts must execute concurrently");
                answer = prompt.contains("从技术") ? "Technical advice" : "Legal advice";
            }
            return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build();
        });
        DecisionModel model = request -> {
            assertEquals(3, request.questions().size());
            return DecisionResponse.builder()
                    .answer("医疗专家", YesNoAnswer.of(0.1))
                    .answer("法律专家", YesNoAnswer.of(0.8))
                    .answer("技术专家", YesNoAnswer.of(0.9)).build();
        };
        AgentConfig config = decisionConfig(chatModel, model);

        var assistant = config.exportAssistant();
        var result = assistant.chat("expert-test", "Fix a Java error", new InvocationParameters());

        assertEquals("Combined advice", result.result());
        assertEquals("Technical advice", result.agenticScope().readState("technicalResponse"));
        assertEquals("Legal advice", result.agenticScope().readState("legalResponse"));
        assertEquals(null, result.agenticScope().readState("medicalResponse"));
        verify(chatModel, times(3)).chat(any(ChatRequest.class));
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

    @Test
    void expertRoutingReceivesOnlyBoundedCurrentSessionConversation() {
        ChatModel chatModel = mock(ChatModel.class);
        AgentConfig config = decisionConfig(chatModel, request -> {
            Map<?, ?> input = (Map<?, ?>) request.input();
            assertEquals("Follow up", input.get("request"));
            assertTrue(input.containsKey("routingContext"));
            String context = input.get("routingContext").toString();
            assertTrue(context.contains("Earlier privacy question"));
            assertTrue(context.contains("Earlier answer"));
            assertTrue(!context.contains("private system instruction"));
            assertTrue(!context.contains("private tool result"));
            assertTrue(context.length() < 6500);
            return relevanceResponse(0.1, 0.2, 0.3);
        });
        RedisChatMemoryStore store = (RedisChatMemoryStore) ReflectionTestUtils.getField(config, "redisChatMemoryStore");
        when(store.getMessages("current-session")).thenReturn(List.of(
                SystemMessage.from("private system instruction"),
                UserMessage.from("Earlier privacy question"), AiMessage.from("Earlier answer"),
                ToolExecutionResultMessage.from("tool-1", "read", "private tool result"),
                UserMessage.from("x".repeat(12000))));

        var result = config.exportAssistant().chat("current-session", "Follow up", new InvocationParameters());

        assertTrue(result.result().contains("补充"));
        verify(store).getMessages("current-session");
    }

    private static DecisionResponse relevanceResponse(double medical, double legal, double technical) {
        return DecisionResponse.builder().answer("医疗专家", YesNoAnswer.of(medical))
                .answer("法律专家", YesNoAnswer.of(legal)).answer("技术专家", YesNoAnswer.of(technical)).build();
    }

    @Test
    void parallelExpertsRetainTrustedMemoryIdentityAndDistinctRecallPolicies() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("Expert answer")).build());
        AgentConfig config = decisionConfig(chat, request -> relevanceResponse(0.1, 0.8, 0.9));
        LongTermMemoryRuntime runtime = mock(LongTermMemoryRuntime.class);
        when(runtime.recall(any())).thenReturn(MemoryRecallResult.empty());
        ReflectionTestUtils.setField(config, "conversationContextAugmentorFactory",
                new ConversationContextAugmentorFactory(runtime, new AgentMemoryPolicyCatalog()));
        var identity = new MemoryInvocationContext(7L, "export-assistant", "root-session", 17L, "actual-session", null);

        var result = config.exportAssistant().chat("memory-session", "Software privacy design", identity.toInvocationParameters());

        assertEquals("Expert answer", result.result());
        var recalls = ArgumentCaptor.forClass(MemoryRecallCommand.class);
        verify(runtime, times(2)).recall(recalls.capture());
        assertEquals(java.util.Set.of("export-assistant.legal-expert", "export-assistant.technical-expert"),
                recalls.getAllValues().stream().map(command -> command.context().logicalAgentId()).collect(java.util.stream.Collectors.toSet()));
        for (MemoryRecallCommand command : recalls.getAllValues()) {
            assertEquals(7L, command.context().userId());
            assertEquals("root-session", command.context().memoryRunId());
            assertEquals("actual-session", command.context().actualSessionId());
            assertEquals(17L, command.context().sourceExecutionId());
        }
    }

    private static AgentConfig decisionConfig(ChatModel chatModel, DecisionModel model) {
        AgentConfig config = new AgentConfig();
        RedisChatMemoryStore store = mock(RedisChatMemoryStore.class);
        when(store.getMessages(any())).thenReturn(List.of());
        ReflectionTestUtils.setField(config, "chatModel", chatModel);
        ReflectionTestUtils.setField(config, "jevDecisionNodes", new JevDecisionNodes(model));
        ReflectionTestUtils.setField(config, "typeSafeDecisionProperties", new TypeSafeDecisionProperties());
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
