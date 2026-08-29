package com.h.agent.observability.langchain4j;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.semantic.SemanticContent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;

/**
 * Decorates the real synchronous ChatModel: one generation observation per model call.
 */
public final class ObservingChatModel implements ChatModel {

    private final ChatModel delegate;
    private final AgentObservability observability;
    private final String providerName;

    public ObservingChatModel(ChatModel delegate, AgentObservability observability, String providerName) {
        this.delegate = delegate;
        this.observability = observability;
        this.providerName = providerName;
    }

    public ChatModel delegate() {
        return delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        AgentObservation observation = startGeneration(request);
        try {
            ChatResponse response = delegate.chat(request);
            recordCompletion(observation, response);
            return response;
        } catch (RuntimeException ex) {
            observation.fail(ex);
            throw ex;
        } catch (Error ex) {
            observation.fail(ex);
            throw ex;
        }
    }

    private AgentObservation startGeneration(ChatRequest request) {
        AgentObservation observation = observability.span(
                ObservationSpec.of("gen_ai." + safeModel(request.modelName()), HObsKind.GENERATION, "langchain4j"),
                observability.currentContext());
        observation.attribute(HAttrs.GEN_AI_SYSTEM, providerName);
        if (request.modelName() != null) {
            observation.attribute(HAttrs.GEN_AI_REQUEST_MODEL, request.modelName());
        }
        if (request.toolSpecifications() != null) {
            observation.attribute("gen_ai.request.tool_count", String.valueOf(request.toolSpecifications().size()));
        }
        observation.input(Lc4jSemantic.fromMessages(request.messages()));
        return observation;
    }

    private void recordCompletion(AgentObservation observation, ChatResponse response) {
        if (response != null && response.modelName() != null) {
            observation.attribute(HAttrs.GEN_AI_RESPONSE_MODEL, response.modelName());
        }
        TokenUsage usage = response == null ? null : response.tokenUsage();
        if (usage != null) {
            observation.usage(
                    usage.inputTokenCount(),
                    usage.outputTokenCount(),
                    usage.totalTokenCount());
        }
        SemanticContent output = response == null ? null : Lc4jSemantic.fromAiMessage(response.aiMessage());
        if (output != null) {
            observation.output(output);
        }
        observation.succeed();
    }

    private static String safeModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return "model";
        }
        return modelName.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
