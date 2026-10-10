package com.h.agent.observability.langchain4j;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.SemanticMessage;
import com.h.agent.observability.semantic.JsonBlock;
import dev.langchain4j.internal.Json;
import dev.langchain4j.model.decision.request.Question;
import dev.langchain4j.model.decision.request.ChoiceQuestion;
import dev.langchain4j.model.decision.request.ScaleQuestion;
import dev.langchain4j.model.decision.request.YesNoQuestion;
import dev.langchain4j.model.decision.response.DecisionAnswer;
import dev.langchain4j.model.decision.response.ChoiceAnswer;
import dev.langchain4j.model.decision.response.ScaleAnswer;
import dev.langchain4j.model.decision.response.YesNoAnswer;
import dev.langchain4j.model.decision.listener.DecisionModelListener;
import dev.langchain4j.model.decision.listener.DecisionModelRequestContext;
import dev.langchain4j.model.decision.listener.DecisionModelResponseContext;
import dev.langchain4j.model.decision.listener.DecisionModelErrorContext;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** Model callbacks are best-effort in LangChain4j and share per-call attributes. */
public final class ObservingDecisionModelListener implements DecisionModelListener {

    private final Object observationKey = new Object();
    private final AgentObservability observability;

    public ObservingDecisionModelListener(AgentObservability observability) {
        this.observability = observability;
    }

    @Override
    public void onRequest(DecisionModelRequestContext context) {
        String model = context.decisionRequest().modelName();
        String safeModel = model == null ? "model" : model.replaceAll("[^A-Za-z0-9._-]", "_");
        AgentObservation observation = observability.span(
                ObservationSpec.of("gen_ai.decision." + safeModel, HObsKind.GENERATION, "langchain4j"),
                observability.currentContext());
        context.attributes().put(observationKey, observation);
        try {
            observation.attribute(HAttrs.GEN_AI_SYSTEM, context.modelProvider().name().toLowerCase(java.util.Locale.ROOT));
            if (model != null) {
                observation.attribute(HAttrs.GEN_AI_REQUEST_MODEL, model);
            }
            Map<String, Object> questions = new LinkedHashMap<>();
            context.decisionRequest().questions().forEach((key, question) -> questions.put(key, questionData(question)));
            observation.input(content("user", Map.of("state", context.decisionRequest().input(), "questions", questions)));
        } catch (RuntimeException error) {
            context.attributes().remove(observationKey);
            observation.fail(error);
            throw error;
        }
    }

    @Override
    public void onResponse(DecisionModelResponseContext context) {
        AgentObservation observation = (AgentObservation) context.attributes().remove(observationKey);
        if (observation == null) {
            return;
        }
        try (observation) {
            var response = context.decisionResponse();
            if (response.modelName() != null) {
                observation.attribute(HAttrs.GEN_AI_RESPONSE_MODEL, response.modelName());
            }
            var usage = response.tokenUsage();
            if (usage != null) {
                observation.usage(usage.inputTokenCount(), usage.outputTokenCount(), usage.totalTokenCount());
            }
            Map<String, Object> answers = new LinkedHashMap<>();
            response.answers().forEach((key, answer) -> answers.put(key, answerData(answer)));
            observation.output(content("assistant", answers));
            observation.succeed();
        }
    }

    @Override
    public void onError(DecisionModelErrorContext context) {
        AgentObservation observation = (AgentObservation) context.attributes().remove(observationKey);
        if (observation != null) {
            try (observation) {
                observation.fail(context.error());
            }
        }
    }

    private static SemanticContent content(String role, Object data) {
        return SemanticContent.ofMessages(List.of(
                new SemanticMessage(role, List.of(new JsonBlock(Json.toJson(data))))));
    }

    private static Map<String, Object> questionData(Question question) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("text", question.text());
        switch (question) {
            case ChoiceQuestion choice -> {
                data.put("type", "choice");
                data.put("options", choice.options());
            }
            case ScaleQuestion scale -> {
                data.put("type", "scale");
                data.put("levels", scale.levels());
                data.put("descriptions", scale.levelDescriptions());
            }
            case YesNoQuestion yesNo -> {
                data.put("type", "yes_no");
                data.put("yes_when", yesNo.yesWhen());
                data.put("no_when", yesNo.noWhen());
            }
            default -> data.put("type", question.getClass().getSimpleName());
        }
        return data;
    }

    private static Map<String, Object> answerData(DecisionAnswer answer) {
        Map<String, Object> data = new LinkedHashMap<>();
        switch (answer) {
            case ChoiceAnswer choice -> {
                data.put("type", "choice");
                data.put("value", choice.value());
                data.put("probabilities", choice.probabilities());
                data.put("confidence", choice.confidence());
            }
            case ScaleAnswer scale -> {
                data.put("type", "scale");
                data.put("mean", scale.mean());
                data.put("probabilities", scale.probabilities());
                data.put("confidence", scale.confidence());
            }
            case YesNoAnswer yesNo -> {
                data.put("type", "yes_no");
                data.put("probability", yesNo.probability());
            }
            default -> data.put("type", answer.getClass().getSimpleName());
        }
        return data;
    }
}
