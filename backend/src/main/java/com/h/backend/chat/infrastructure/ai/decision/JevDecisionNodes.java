package com.h.backend.chat.infrastructure.ai.decision;

import com.h.backend.chat.infrastructure.ai.Agents;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.decision.DecisionServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Adapts typed Jev decisions to the existing workflow state contract. */
public final class JevDecisionNodes {

    private static final Logger log = LoggerFactory.getLogger(JevDecisionNodes.class);
    private final JevDecisions decisions;

    public JevDecisionNodes(DecisionModel model) {
        this.decisions = model == null ? null : DecisionServices.create(JevDecisions.class, model);
    }

    public Object categoryRouter(ChatModel chatModel, AgentListener listener) {
        if (decisions == null) {
            return AgenticServices.agentBuilder(Agents.CategoryRouter.class)
                    .chatModel(chatModel).listener(listener).outputKey("category").build();
        }
        Agents.CategoryRouter fallback = AiServices.create(Agents.CategoryRouter.class, chatModel);
        return AgenticServices.nonAiAgentBuilder(scope -> {
                    String request = (String) scope.readState("request");
                    try {
                        return decisions.classify(request).value();
                    } catch (RuntimeException error) {
                        log.warn("Jev classification failed ({}); using LLM fallback", error.getClass().getSimpleName());
                        return fallback.classify(request);
                    }
                })
                .name("CategoryRouter")
                .description("对用户请求进行分类")
                .inputKey(String.class, "request")
                .outputKey("category")
                .outputType(Agents.RequestCategory.class)
                .listener(listener)
                .build();
    }

    public Object styleScorer(ChatModel chatModel, AgentListener listener) {
        if (decisions == null) {
            return AgenticServices.agentBuilder(Agents.StyleScorer.class)
                    .chatModel(chatModel).listener(listener).outputKey("score").build();
        }
        Agents.StyleScorer fallback = AiServices.create(Agents.StyleScorer.class, chatModel);
        return AgenticServices.nonAiAgentBuilder(scope -> {
                    String story = (String) scope.readState("story");
                    String style = (String) scope.readState("style");
                    try {
                        // Jev's mean is an ordinal level, not a probability or confidence.
                        return decisions.scoreStyle(story, style).mean()
                                / (JevDecisions.StyleMatchLevel.values().length - 1);
                    } catch (RuntimeException error) {
                        log.warn("Jev style scoring failed ({}); using LLM fallback", error.getClass().getSimpleName());
                        return fallback.scoreStyle(story, style);
                    }
                })
                .name("风格评分器")
                .description("依据故事与指定风格的契合度进行打分")
                .inputKeys(String.class, "story", String.class, "style")
                .outputKey("score")
                .outputType(Double.class)
                .listener(listener)
                .build();
    }
}
