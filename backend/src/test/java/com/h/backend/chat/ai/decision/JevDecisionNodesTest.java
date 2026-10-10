package com.h.backend.chat.ai.decision;

import com.h.backend.chat.infrastructure.ai.Agents;
import com.h.backend.chat.infrastructure.ai.decision.JevDecisionNodes;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.planner.AgentInstance;
import dev.langchain4j.agentic.planner.AgenticSystemTopology;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.response.ChoiceAnswer;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.ScaleAnswer;
import dev.langchain4j.model.decision.request.ScaleQuestion;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class JevDecisionNodesTest {

    public interface ClassificationFlow {
        ResultWithAgenticScope<Agents.RequestCategory> run(@V("request") String request);
    }

    public interface ScoringFlow {
        ResultWithAgenticScope<Double> run(@V("story") String story, @V("style") String style);
    }

    @ParameterizedTest
    @EnumSource(Agents.RequestCategory.class)
    void routesUsingJevAndPreservesCategoryStateWithoutCallingChatModel(Agents.RequestCategory category) {
        ChatModel chatModel = mock(ChatModel.class);
        DecisionModel model = request -> {
            assertEquals(Map.of("request", "How do I fix a Java error?"), request.input());
            return DecisionResponse.builder()
                    .answer(request.questions().keySet().iterator().next(),
                            ChoiceAnswer.builder().value(category.name())
                                    .probabilities(Map.of(category.name(), 1.0)).build())
                    .build();
        };
        Object router = new JevDecisionNodes(model).categoryRouter(chatModel, mock(AgentListener.class));
        ClassificationFlow flow = AgenticServices.sequenceBuilder(ClassificationFlow.class)
                .subAgents(router).outputKey("category").build();

        ResultWithAgenticScope<Agents.RequestCategory> result = flow.run("How do I fix a Java error?");

        assertEquals(category, result.result());
        assertEquals(category, result.agenticScope().readState("category"));
        verifyNoInteractions(chatModel);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "4, 1", "3.2, 0.8", "2.5, 0.625"})
    void normalizesScaleMeanInsteadOfConfidence(double mean, double expected) {
        ChatModel chatModel = mock(ChatModel.class);
        AgentListener listener = mock(AgentListener.class);
        DecisionModel model = request -> {
            assertEquals(Map.of("story", "A short story", "style", "humorous"), request.input());
            String key = request.questions().keySet().iterator().next();
            assertEquals(5, ((ScaleQuestion) request.questions().get(key)).levels().size());
            return DecisionResponse.builder()
                    .answer(key, ScaleAnswer.builder().mean(mean).confidence(0.1).build()).build();
        };
        Object scorer = new JevDecisionNodes(model).styleScorer(chatModel, listener);
        ScoringFlow flow = AgenticServices.sequenceBuilder(ScoringFlow.class)
                .subAgents(scorer).outputKey("score").build();

        var result = flow.run("A short story", "humorous");

        assertEquals(expected, result.result(), 1e-9);
        assertEquals(expected, (Double) result.agenticScope().readState("score"), 1e-9);
        verifyNoInteractions(chatModel);
        verify(listener).beforeAgentInvocation(any());
        verify(listener).afterAgentInvocation(any());
    }

    @Test
    void failedJevClassificationFallsBackToOriginalLlm() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("MEDICAL")).build());
        DecisionModel unavailable = request -> { throw new IllegalStateException("unavailable"); };
        ClassificationFlow flow = AgenticServices.sequenceBuilder(ClassificationFlow.class)
                .subAgents(new JevDecisionNodes(unavailable).categoryRouter(chatModel, mock(AgentListener.class)))
                .outputKey("category").build();

        assertEquals(Agents.RequestCategory.MEDICAL, flow.run("A health question").result());
        verify(chatModel).chat(any(ChatRequest.class));
    }

    @Test
    void failedJevScoringFallsBackToOriginalLlm() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("0.9")).build());
        DecisionModel unavailable = request -> { throw new IllegalStateException("timeout"); };
        ScoringFlow flow = AgenticServices.sequenceBuilder(ScoringFlow.class)
                .subAgents(new JevDecisionNodes(unavailable).styleScorer(chatModel, mock(AgentListener.class)))
                .outputKey("score").build();

        assertEquals(0.9, flow.run("A short story", "humorous").result());
        verify(chatModel).chat(any(ChatRequest.class));
    }

    @Test
    void disabledJevKeepsOriginalChatModelNodes() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("LEGAL")).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("0.85")).build());
        var nodes = new JevDecisionNodes(null);
        Object router = nodes.categoryRouter(chatModel, mock(AgentListener.class));
        Object scorer = nodes.styleScorer(chatModel, mock(AgentListener.class));
        assertEquals(AgenticSystemTopology.AI_AGENT, ((AgentInstance) router).topology());
        assertEquals(AgenticSystemTopology.AI_AGENT, ((AgentInstance) scorer).topology());
        assertEquals(Agents.RequestCategory.LEGAL, ((Agents.CategoryRouter) router).classify("Contract question"));
        assertEquals(0.85, ((Agents.StyleScorer) scorer).scoreStyle("A short story", "humorous"));
    }

    @Test
    void invalidJevScoreDoesNotEnterWorkflowStateAndUsesLlmFallback() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("0.7")).build());
        DecisionModel invalid = request -> DecisionResponse.builder()
                .answer(request.questions().keySet().iterator().next(), ScaleAnswer.builder().mean(7.0).build()).build();
        ScoringFlow flow = AgenticServices.sequenceBuilder(ScoringFlow.class)
                .subAgents(new JevDecisionNodes(invalid).styleScorer(chatModel, mock(AgentListener.class)))
                .outputKey("score").build();

        var result = flow.run("A short story", "humorous");

        assertEquals(0.7, result.result());
        assertEquals(0.7, result.agenticScope().readState("score"));
    }

    @Test
    void bothModelsFailingPropagatesFailureInsteadOfInventingCategory() {
        ChatModel chatModel = mock(ChatModel.class);
        when(chatModel.chat(any(ChatRequest.class))).thenThrow(new IllegalStateException("LLM unavailable"));
        DecisionModel unavailable = request -> { throw new IllegalStateException("Jev unavailable"); };
        ClassificationFlow flow = AgenticServices.sequenceBuilder(ClassificationFlow.class)
                .subAgents(new JevDecisionNodes(unavailable).categoryRouter(chatModel, mock(AgentListener.class)))
                .outputKey("category").build();

        assertThrows(RuntimeException.class, () -> flow.run("A technical question"));
    }

    @ParameterizedTest
    @CsvSource({"3.2, 1", "2.0, 5"})
    void scoringLoopPreservesExitThresholdAndIterationLimit(double mean, int expectedCalls) {
        AtomicInteger calls = new AtomicInteger();
        DecisionModel model = request -> {
            calls.incrementAndGet();
            return DecisionResponse.builder()
                    .answer(request.questions().keySet().iterator().next(),
                            ScaleAnswer.builder().mean(mean).build()).build();
        };
        Object scorer = new JevDecisionNodes(model).styleScorer(mock(ChatModel.class), mock(AgentListener.class));
        ScoringFlow flow = AgenticServices.loopBuilder(ScoringFlow.class)
                .subAgents(scorer).maxIterations(5)
                .exitCondition(scope -> (Double) scope.readState("score", 0.0) >= 0.8)
                .outputKey("score").build();

        flow.run("A short story", "humorous");

        assertEquals(expectedCalls, calls.get());
    }
}
