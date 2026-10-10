package com.h.backend.chat.ai.decision;

import com.h.backend.chat.infrastructure.ai.Agents;
import com.h.backend.chat.infrastructure.ai.decision.JevDecisions;
import dev.langchain4j.model.typesafe.TypeSafeDecisionModel;
import dev.langchain4j.service.decision.DecisionServices;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Opt-in, two real API calls; never reads credentials from source or test resources. */
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
}
