package com.h.backend.chat.config;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.NoopAgentObservability;
import com.h.backend.chat.infrastructure.ai.decision.JevDecisionConfig;
import com.h.backend.chat.infrastructure.ai.decision.JevDecisionNodes;
import com.h.backend.chat.infrastructure.config.TypeSafeDecisionProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class JevDecisionConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(JevDecisionConfig.class)
            .withBean(AgentObservability.class, NoopAgentObservability::getInstance);

    @Test
    void disabledByDefaultDoesNotRequireCredentials() {
        runner.run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(JevDecisionNodes.class);
            assertThat(context.getBean(TypeSafeDecisionProperties.class).isEnabled()).isFalse();
        });
    }

    @Test
    void explicitlyEnabledWithoutKeyFailsFast() {
        runner.withPropertyValues("typesafe.enabled=true").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "TYPESAFE_API_KEY is required when typesafe.enabled=true");
        });
    }

    @Test
    void enabledConfigurationBindsModelAndTimeoutWithoutMakingNetworkCalls() {
        runner.withPropertyValues("typesafe.enabled=true", "typesafe.api-key=test-only-key",
                "typesafe.model-name=jev-test", "typesafe.timeout=1500ms", "typesafe.max-retries=0")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(JevDecisionNodes.class);
                    var properties = context.getBean(TypeSafeDecisionProperties.class);
                    assertThat(properties.getModelName()).isEqualTo("jev-test");
                    assertThat(properties.getTimeout().toMillis()).isEqualTo(1500);
                });
    }

    @Test
    void nonPositiveTimeoutIsRejected() {
        runner.withPropertyValues("typesafe.enabled=true", "typesafe.api-key=test-only-key",
                "typesafe.timeout=0ms").run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseMessage("typesafe.timeout must be positive");
                });
    }

    @Test
    void expertActivationThresholdIsBoundWhenJevIsDisabled() {
        runner.withPropertyValues("typesafe.expert-activation-threshold=0.75").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(TypeSafeDecisionProperties.class).getExpertActivationThreshold()).isEqualTo(0.75);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "1", "-0.1", "NaN"})
    void invalidExpertThresholdFailsFastEvenWithJevDisabled(String value) {
        runner.withPropertyValues("typesafe.expert-activation-threshold=" + value).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseMessage(
                    "typesafe.expert-activation-threshold must be strictly between 0 and 1");
        });
    }
}
