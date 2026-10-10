package com.h.backend.chat.infrastructure.ai.decision;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.langchain4j.ObservingDecisionModelListener;
import com.h.backend.chat.infrastructure.config.TypeSafeDecisionProperties;
import dev.langchain4j.model.typesafe.TypeSafeDecisionModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

import java.util.List;

@Configuration
@EnableConfigurationProperties(TypeSafeDecisionProperties.class)
public class JevDecisionConfig {

    @Bean
    public JevDecisionNodes jevDecisionNodes(TypeSafeDecisionProperties properties, AgentObservability observability) {
        if (!properties.isEnabled()) {
            return new JevDecisionNodes(null);
        }
        properties.validateEnabled();
        var model = TypeSafeDecisionModel.builder()
                .apiKey(properties.getApiKey())
                .baseUrl(properties.getBaseUrl())
                .modelName(properties.getModelName())
                .timeout(properties.getTimeout())
                .maxRetries(properties.getMaxRetries())
                .logRequests(false)
                .logResponses(false)
                .listeners(List.of(new ObservingDecisionModelListener(observability)))
                .build();
        return new JevDecisionNodes(model);
    }
}
