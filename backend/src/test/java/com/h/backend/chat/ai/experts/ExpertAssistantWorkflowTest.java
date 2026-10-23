package com.h.backend.chat.ai.experts;

import com.h.backend.chat.infrastructure.ai.experts.ExpertAssistantWorkflow;
import com.h.agent.observability.AgentObservabilityTesting;
import com.h.agent.observability.AgentObservabilityConfig;
import com.h.agent.observability.langchain4j.ObservingAgentListener;
import com.h.agent.observability.lifecycle.AgentExecutionStart;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.observability.AgentRequest;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.model.decision.response.DecisionResponse;
import dev.langchain4j.model.decision.response.YesNoAnswer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.function.Supplier;
import java.util.function.Function;
import dev.langchain4j.data.message.UserMessage;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.any;

class ExpertAssistantWorkflowTest {
    private final AgentListener listener = new AgentListener() {};

    @Test
    void noMatchingExpertsReturnsClarificationWithoutCallingAnLlm() {
        ChatModel chat = mock(ChatModel.class);
        var assistant = ExpertAssistantWorkflow.build(relevance(0.1, 0.2, 0.3), chat, listener, 0.6,
                expert("医疗专家", "medicalResponse", () -> { throw new AssertionError("Not selected"); }),
                expert("法律专家", "legalResponse", () -> { throw new AssertionError("Not selected"); }),
                expert("技术专家", "technicalResponse", () -> { throw new AssertionError("Not selected"); }));

        var result = assistant.chat("test-session", "Write a cake recipe", new InvocationParameters());

        assertThat(result.result()).contains("医疗", "法律", "技术", "补充");
        verifyNoInteractions(chat);
    }

    @Test
    void failedExpertDoesNotDiscardSuccessfulExpertAnswer() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            String prompt = invocation.<ChatRequest>getArgument(0).messages().toString();
            assertThat(prompt).contains("Technical answer", "法律专家");
            assertThat(prompt).doesNotContain("secret-provider-error");
            return ChatResponse.builder().aiMessage(AiMessage.from("Combined answer")).build();
        });
        var assistant = ExpertAssistantWorkflow.build(relevance(0.1, 0.8, 0.9), chat, listener, 0.6,
                expert("医疗专家", "medicalResponse", () -> "Not selected"),
                expert("法律专家", "legalResponse", () -> { throw new RuntimeException("secret-provider-error"); }),
                expert("技术专家", "technicalResponse", () -> "Technical answer"));

        var result = assistant.chat("test-session", "Design with privacy rules", new InvocationParameters());

        assertThat(result.result()).contains("Combined answer", "法律专家", "未完成");
        assertThat(((java.util.Map<?, ?>) result.agenticScope().readState("expertFailures"))
                .containsKey("法律专家")).isTrue();
    }

    @Test
    void synthesizerFailureReturnsExpertAnswersInStableOrder() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("private summary failure"));
        var assistant = ExpertAssistantWorkflow.build(relevance(0.7, 0.8, 0.9), chat, listener, 0.6,
                expert("医疗专家", "medicalResponse", () -> "Medical answer"),
                expert("法律专家", "legalResponse", () -> "Legal answer"),
                expert("技术专家", "technicalResponse", () -> "Technical answer"));

        var result = assistant.chat("test-session", "Cross-domain request", new InvocationParameters());

        assertThat(result.result()).contains("未完成综合整理", "Medical answer", "Legal answer", "Technical answer")
                .doesNotContain("private summary failure");
        assertThat(result.result().indexOf("Medical answer")).isLessThan(result.result().indexOf("Legal answer"));
        assertThat(result.result().indexOf("Legal answer")).isLessThan(result.result().indexOf("Technical answer"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void disabledOrUnavailableJevUsesMultiSelectLlmFallback(boolean disabled) {
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(
                        "{\"medical\":false,\"legal\":true,\"technical\":true}")).build(),
                ChatResponse.builder().aiMessage(AiMessage.from("Combined answer")).build());
        DecisionModel model = disabled ? null : request -> { throw new RuntimeException("Jev unavailable"); };
        var assistant = ExpertAssistantWorkflow.build(model, chat, listener, 0.6,
                expert("医疗专家", "medicalResponse", () -> { throw new AssertionError("Not selected"); }),
                expert("法律专家", "legalResponse", () -> "Legal answer"),
                expert("技术专家", "technicalResponse", () -> "Technical answer"));

        var result = assistant.chat("test-session", "Technical and legal request", new InvocationParameters());

        assertThat(result.result()).isEqualTo("Combined answer");
        assertThat(result.agenticScope().readState("routingSource")).isEqualTo("llm_fallback");
        assertThat(result.agenticScope().readState("routingProbabilities")).isEqualTo(java.util.Map.of());
        assertThat((java.util.Map<?, ?>) result.agenticScope().readState("expertResponses")).hasSize(2);
    }

    @Test
    void thresholdBoundaryActivatesExactlyTheEligibleExpertAndStillSynthesizes() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("Technical summary")).build());
        var assistant = ExpertAssistantWorkflow.build(relevance(0.5999, 0.1, 0.6), chat, listener, 0.6,
                expert("医疗专家", "medicalResponse", () -> { throw new AssertionError("Below threshold"); }),
                expert("法律专家", "legalResponse", () -> { throw new AssertionError("Below threshold"); }),
                expert("技术专家", "technicalResponse", () -> "Technical answer"));
        var result = assistant.chat("one-expert", "Fix a bug", new InvocationParameters());
        assertThat(result.result()).isEqualTo("Technical summary");
        assertThat(result.agenticScope().readState("routingSource")).isEqualTo("jev");
        assertThat(result.agenticScope().readState("routingProbabilities"))
                .isEqualTo(Map.of("医疗专家", 0.5999, "法律专家", 0.1, "技术专家", 0.6));
    }

    @Test
    void allActivatedExpertsFailWithoutCallingSynthesizer() {
        ChatModel chat = mock(ChatModel.class);
        var assistant = ExpertAssistantWorkflow.build(relevance(0.1, 0.8, 0.9), chat, listener, 0.6,
                expert("医疗专家", "medicalResponse", () -> "Not selected"),
                expert("法律专家", "legalResponse", () -> { throw new RuntimeException("Provider down"); }),
                expert("技术专家", "technicalResponse", () -> " "));
        assertThatThrownBy(() -> assistant.chat("all-failed", "Request", new InvocationParameters()))
                .hasStackTraceContaining("已激活专家均未返回有效回答");
        verifyNoInteractions(chat);
    }

    @Test
    void malformedFallbackFailsInsteadOfActivatingAllExperts() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("{\"legal\":true}")).build());
        var assistant = ExpertAssistantWorkflow.build(null, chat, listener, 0.6,
                expert("医疗专家", "medicalResponse", () -> { throw new AssertionError("Must not run"); }),
                expert("法律专家", "legalResponse", () -> { throw new AssertionError("Must not run"); }),
                expert("技术专家", "technicalResponse", () -> { throw new AssertionError("Must not run"); }));
        assertThatThrownBy(() -> assistant.chat("bad-routing", "Request", new InvocationParameters()))
                .hasStackTraceContaining("all three boolean selections");
    }

    @Test
    void incompleteJevResponseFallsBackToExplicitMultiSelect() {
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from(
                        "{\"medical\":false,\"legal\":false,\"technical\":false}")).build());
        DecisionModel incomplete = request -> DecisionResponse.builder()
                .answer("技术专家", YesNoAnswer.of(0.9)).build();
        var assistant = ExpertAssistantWorkflow.build(incomplete, chat, listener, 0.6,
                expert("医疗专家", "medicalResponse", () -> { throw new AssertionError("Must not run"); }),
                expert("法律专家", "legalResponse", () -> { throw new AssertionError("Must not run"); }),
                expert("技术专家", "technicalResponse", () -> { throw new AssertionError("Must not run"); }));
        var result = assistant.chat("incomplete-routing", "Request", new InvocationParameters());
        assertThat(result.agenticScope().readState("routingSource")).isEqualTo("llm_fallback");
        assertThat(result.result()).contains("补充");
    }

    @Test
    void synthesisObservationReceivesAnswersAndRoutingMetadata() {
        var inputs = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        AgentListener recording = new AgentListener() {
            @Override
            public void beforeAgentInvocation(AgentRequest request) {
                if ("专家回答汇总".equals(request.agentName())) inputs.set(request.inputs());
            }
        };
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenReturn(
                ChatResponse.builder().aiMessage(AiMessage.from("Summary")).build());
        var assistant = ExpertAssistantWorkflow.build(relevance(0.1, 0.1, 0.9), chat, recording, 0.6,
                expert("医疗专家", "medicalResponse", () -> "Not selected"),
                expert("法律专家", "legalResponse", () -> "Not selected"),
                expert("技术专家", "technicalResponse", () -> "Technical answer"));
        assistant.chat("metadata", "Request", new InvocationParameters());
        assertThat(inputs.get()).containsEntry("request", "Request")
                .containsEntry("expertResponses", Map.of("技术专家", "Technical answer"))
                .containsEntry("routingSource", "jev").containsEntry("routingThreshold", 0.6)
                .containsEntry("routingProbabilities", Map.of("医疗专家", 0.1, "法律专家", 0.1, "技术专家", 0.9));
    }

    @ParameterizedTest
    @ValueSource(strings = {"security", "cancellation", "programming"})
    void nonRecoverableExpertFailuresAreNotConvertedToAnswers(String kind) {
        ChatModel chat = mock(ChatModel.class);
        RuntimeException error = switch (kind) {
            case "security" -> new SecurityException("Access denied");
            case "cancellation" -> new CancellationException("Cancelled");
            default -> new NullPointerException("Programming error");
        };
        var assistant = ExpertAssistantWorkflow.build(relevance(0.1, 0.1, 0.9), chat, listener, 0.6,
                expert("医疗专家", "medicalResponse", () -> "Not selected"),
                expert("法律专家", "legalResponse", () -> "Not selected"),
                expert("技术专家", "technicalResponse", () -> { throw error; }));
        assertThatThrownBy(() -> assistant.chat("not-recoverable", "Request", new InvocationParameters()))
                .hasStackTraceContaining(error.getMessage());
        verifyNoInteractions(chat);
    }

    @Test
    void concurrentSessionsDoNotShareSelectionsFailuresOrConversation() throws Exception {
        CountDownLatch bothRouting = new CountDownLatch(2);
        DecisionModel routing = request -> {
            bothRouting.countDown();
            try {
                assertThat(bothRouting.await(3, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(interrupted);
            }
            Map<?, ?> input = (Map<?, ?>) request.input();
            boolean first = "legal-request".equals(input.get("request"));
            assertThat(input.get("routingContext").toString())
                    .contains(first ? "first-private" : "second-private")
                    .doesNotContain(first ? "second-private" : "first-private");
            return relevance(first ? 0.1 : 0.2, first ? 0.8 : 0.1, 0.9).decide(request);
        };
        ChatModel chat = mock(ChatModel.class);
        when(chat.chat(any(ChatRequest.class))).thenAnswer(invocation -> {
            String prompt = invocation.<ChatRequest>getArgument(0).messages().toString();
            return ChatResponse.builder().aiMessage(AiMessage.from(
                    prompt.contains("legal-request") ? "First summary" : "Second summary")).build();
        });
        var assistant = ExpertAssistantWorkflow.build(routing, chat, listener, 0.6,
                id -> java.util.List.of(UserMessage.from(id.equals("first") ? "first-private" : "second-private")),
                expert("医疗专家", "medicalResponse", () -> "Not selected"),
                expert("法律专家", "legalResponse", () -> { throw new RuntimeException("Legal unavailable"); }),
                requestExpert("技术专家", "technicalResponse", request -> "Answer for " + request));
        var first = CompletableFuture.supplyAsync(() -> assistant.chat("first", "legal-request", new InvocationParameters()));
        var second = CompletableFuture.supplyAsync(() -> assistant.chat("second", "technical-request", new InvocationParameters()));
        var firstResult = first.get(5, TimeUnit.SECONDS);
        var secondResult = second.get(5, TimeUnit.SECONDS);
        assertThat(firstResult.result()).contains("First summary", "法律专家", "未完成");
        assertThat(secondResult.result()).isEqualTo("Second summary");
        assertThat(secondResult.agenticScope().readState("expertFailures")).isEqualTo(Map.of());
        assertThat(firstResult.agenticScope().readState("technicalResponse")).isEqualTo("Answer for legal-request");
        assertThat(secondResult.agenticScope().readState("technicalResponse")).isEqualTo("Answer for technical-request");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void realFrameworkParallelExecutionKeepsOneObservationTree(boolean blankMedicalAnswer) {
        var exporter = InMemorySpanExporter.create();
        var config = AgentObservabilityConfig.builder().baseUrl("http://langfuse.local")
                .publicKey("pk-test").secretKey("sk-test").rootRatio(1).scheduleDelayMillis(10).build();
        var observability = AgentObservabilityTesting.build(config, exporter);
        try {
            ChatModel chat = mock(ChatModel.class);
            when(chat.chat(any(ChatRequest.class))).thenReturn(
                    ChatResponse.builder().aiMessage(AiMessage.from("Summary")).build());
            var assistant = ExpertAssistantWorkflow.build(relevance(0.7, 0.8, 0.9), chat,
                    new ObservingAgentListener(observability), 0.6,
                    expert("医疗专家", "medicalResponse", () -> blankMedicalAnswer ? " " : "Medical answer"),
                    expert("法律专家", "legalResponse", () -> "Legal answer"),
                    expert("技术专家", "technicalResponse", () -> "Technical answer"));
            var execution = observability.start(new AgentExecutionStart("expert.run", "session", 7L,
                    "export-assistant", "session", "CHAT", "run", java.util.List.of(), Map.of(), null));
            try (var ignored = execution.scope()) {
                assistant.chat("session", "Request", new InvocationParameters());
            }
            execution.succeed(null);
            AgentObservabilityTesting.flush(observability);
            var spans = exporter.getFinishedSpanItems();
            var router = spans.stream().filter(span -> span.getName().contains("多专家路由")).findFirst().orElseThrow();
            assertThat(spans.stream().filter(span -> span.getName().contains("专家回答汇总")).toList()).hasSize(1);
            var experts = spans.stream().filter(span -> span.getName().startsWith("agent.医疗专家$")
                    || span.getName().startsWith("agent.法律专家$") || span.getName().startsWith("agent.技术专家$")).toList();
            assertThat(experts).as("Exported spans: %s", spans.stream().map(span -> span.getName()).toList()).hasSize(3);
            for (var expert : experts) {
                assertThat(expert.getParentSpanId()).isEqualTo(router.getSpanId());
                assertThat(expert.getTraceId()).isEqualTo(execution.traceId());
            }
        } finally {
            observability.close();
        }
    }

    private static DecisionModel relevance(double medical, double legal, double technical) {
        return request -> DecisionResponse.builder()
                .answer("医疗专家", YesNoAnswer.of(medical))
                .answer("法律专家", YesNoAnswer.of(legal))
                .answer("技术专家", YesNoAnswer.of(technical)).build();
    }

    private static Object expert(String name, String outputKey, Supplier<String> answer) {
        return requestExpert(name, outputKey, request -> answer.get());
    }

    private static Object requestExpert(String name, String outputKey, Function<String, String> answer) {
        return AgenticServices.nonAiAgentBuilder(scope -> answer.apply((String) scope.readState("request")))
                .name(name).description(name + "领域的问题")
                .inputKey(String.class, "request").outputKey(outputKey)
                .outputType(String.class).build();
    }
}
