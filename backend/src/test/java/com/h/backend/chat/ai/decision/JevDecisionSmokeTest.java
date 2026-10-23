package com.h.backend.chat.ai.decision;

import com.h.backend.chat.infrastructure.ai.Agents;
import com.h.backend.chat.infrastructure.ai.decision.JevDecisions;
import com.h.backend.chat.infrastructure.ai.experts.ExpertAssistantWorkflow;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.typesafe.TypeSafeDecisionModel;
import dev.langchain4j.service.decision.DecisionServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in real API calls; never reads credentials from source or test resources. */
@EnabledIfEnvironmentVariable(named = "TYPESAFE_SMOKE_TEST", matches = "true")
class JevDecisionSmokeTest {

    @Test
    void publishedSdkCanClassifyAndScoreThroughDecisionServices() {
        var model = TypeSafeDecisionModel.builder()
                .apiKey(System.getenv("TYPESAFE_API_KEY"))
                .modelName(System.getenv().getOrDefault("TYPESAFE_MODEL_NAME", "jev-1.13.0"))
                .timeout(Duration.ofSeconds(2))
                .maxRetries(0)
                .logRequests(false).logResponses(false).build();
        JevDecisions decisions = DecisionServices.create(JevDecisions.class, model);

        var category = decisions.classify("Java 编译提示找不到符号，如何修复代码？");
        assertEquals(Agents.RequestCategory.TECHNICAL, category.value());
        assertFalse(category.probabilities().isEmpty());

        var style = decisions.scoreStyle("宇航员刚踏上月球，就收到老板的消息：今天的日报呢？他说，信号不好。老板回道，没事，我已经给月球装了 Wi-Fi。", "幽默诙谐");
        double normalized = style.mean() / 4;
        assertTrue(normalized >= 0 && normalized <= 1);
        assertFalse(style.probabilities().isEmpty());
        System.out.printf("Jev smoke: category=%s, normalizedStyleScore=%.3f%n", category.value(), normalized);
    }

    @Test
    void publishedPlannerCanRouteExpertsUsingThreeIndependentJevQuestions() {
        var model = TypeSafeDecisionModel.builder()
                .apiKey(System.getenv("TYPESAFE_API_KEY"))
                .modelName(System.getenv().getOrDefault("TYPESAFE_MODEL_NAME", "jev-1.13.0"))
                .timeout(Duration.ofSeconds(2)).maxRetries(0)
                .logRequests(false).logResponses(false).build();
        ChatModel summaryOnly = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                assertFalse(request.messages().toString().contains("路由输入"), "Smoke must not silently use LLM routing");
                return ChatResponse.builder().aiMessage(AiMessage.from("Combined answer")).build();
            }
        };
        var medical = AgenticServices.nonAiAgentBuilder(scope -> "Medical answer")
                .name("医疗专家").description("处理疾病、症状、药物和治疗问题，不处理软件设计或隐私合规")
                .inputKey(String.class, "request").outputType(String.class).outputKey("medicalResponse").build();
        var legal = AgenticServices.nonAiAgentBuilder(scope -> "Legal answer")
                .name("法律专家").description("处理法律、合同、权利义务和隐私合规问题")
                .inputKey(String.class, "request").outputType(String.class).outputKey("legalResponse").build();
        var technical = AgenticServices.nonAiAgentBuilder(scope -> "Technical answer")
                .name("技术专家").description("处理编程、软件架构、数据库和工程故障问题")
                .inputKey(String.class, "request").outputType(String.class).outputKey("technicalResponse").build();
        var assistant = ExpertAssistantWorkflow.build(model, summaryOnly, new AgentListener() {}, 0.6,
                medical, legal, technical);
        var result = assistant.chat("jev-smoke", "设计保存用户个人数据的软件系统，讨论技术架构和隐私合规。",
                new InvocationParameters());
        assertEquals("jev", result.agenticScope().readState("routingSource"));
        assertEquals(3, ((java.util.Map<?, ?>) result.agenticScope().readState("routingProbabilities")).size());
        assertEquals("Combined answer", result.result());
    }
}
