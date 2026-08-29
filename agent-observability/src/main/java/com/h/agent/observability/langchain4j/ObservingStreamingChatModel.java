package com.h.agent.observability.langchain4j;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.lifecycle.ObservationScope;
import com.h.agent.observability.semantic.SemanticContent;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.TokenUsage;

import java.util.List;

/**
 * Decorates the real StreamingChatModel: one generation observation per model call,
 * parented to the observation context that is current when the call starts.
 * <p>
 * Callbacks arrive on the provider IO thread. The handler wrapper forwards every
 * {@link StreamingChatResponseHandler} method (interface defaults are empty, so missing
 * overrides would silently drop tool calls and thinking) and runs the completion callback
 * inside the generation scope, so the next-round request build (dynamic tool providers,
 * follow-up model call) stays parented to the same trace.
 */
public final class ObservingStreamingChatModel implements StreamingChatModel {

    private final StreamingChatModel delegate;
    private final AgentObservability observability;
    private final String providerName;

    public ObservingStreamingChatModel(StreamingChatModel delegate,
                                       AgentObservability observability,
                                       String providerName) {
        this.delegate = delegate;
        this.observability = observability;
        this.providerName = providerName;
    }

    public StreamingChatModel delegate() {
        return delegate;
    }

    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        AgentObservation observation = startGeneration(request);
        delegate.chat(request, new ObservingHandler(handler, observation));
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

    private static String safeModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return "model";
        }
        return modelName.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private final class ObservingHandler implements StreamingChatResponseHandler {

        private final StreamingChatResponseHandler delegate;
        private final AgentObservation observation;
        private final StringBuilder partialText = new StringBuilder();

        ObservingHandler(StreamingChatResponseHandler delegate, AgentObservation observation) {
            this.delegate = delegate;
            this.observation = observation;
        }

        @Override
        public void onPartialResponse(String partialResponse) {
            if (partialText.length() < 256 * 1024) {
                partialText.append(partialResponse == null ? "" : partialResponse);
            }
            delegate.onPartialResponse(partialResponse);
        }

        @Override
        public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
            if (partialResponse != null && partialResponse.text() != null && partialText.length() < 256 * 1024) {
                partialText.append(partialResponse.text());
            }
            delegate.onPartialResponse(partialResponse, context);
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking) {
            delegate.onPartialThinking(partialThinking);
        }

        @Override
        public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
            delegate.onPartialThinking(partialThinking, context);
        }

        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall) {
            delegate.onPartialToolCall(partialToolCall);
        }

        @Override
        public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
            delegate.onPartialToolCall(partialToolCall, context);
        }

        @Override
        public void onCompleteToolCall(CompleteToolCall completeToolCall) {
            delegate.onCompleteToolCall(completeToolCall);
        }

        @Override
        public void onUnmappedRawEvent(Object rawEvent) {
            delegate.onUnmappedRawEvent(rawEvent);
        }

        @Override
        public void onCompleteResponse(ChatResponse completeResponse) {
            try (ObservationScope scope = observability.scope(observation.context())) {
                try {
                    recordCompletion(completeResponse);
                } finally {
                    delegate.onCompleteResponse(completeResponse);
                }
            }
        }

        private void recordCompletion(ChatResponse completeResponse) {
            if (completeResponse != null && completeResponse.modelName() != null) {
                observation.attribute(HAttrs.GEN_AI_RESPONSE_MODEL, completeResponse.modelName());
            }
            TokenUsage usage = completeResponse == null ? null : completeResponse.tokenUsage();
            if (usage != null) {
                observation.usage(
                        usage.inputTokenCount(),
                        usage.outputTokenCount(),
                        usage.totalTokenCount());
            }
            SemanticContent output = completeResponse == null
                    ? null
                    : Lc4jSemantic.fromAiMessage(completeResponse.aiMessage());
            if (output == null && partialText.length() > 0) {
                output = SemanticContent.ofBlocks(List.of(
                        new com.h.agent.observability.semantic.TextBlock(partialText.toString())));
            }
            if (output != null) {
                observation.output(output);
            }
            observation.succeed();
        }

        @Override
        public void onError(Throwable error) {
            try {
                observation.fail(error);
            } finally {
                delegate.onError(error);
            }
        }
    }
}
