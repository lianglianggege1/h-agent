package com.h.backend.chat.infrastructure.ai.experts;

import com.h.backend.chat.infrastructure.ai.carrentalassistant.services.ExportAssistant;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.agent.ErrorRecoveryResult;
import dev.langchain4j.agentic.scope.AgenticScope;
import com.h.backend.chat.infrastructure.ai.TopologyAwareDecisionRouterPlanner;
import dev.langchain4j.agentic.planner.Action;
import dev.langchain4j.agentic.planner.AgentArgument;
import dev.langchain4j.agentic.planner.Planner;
import dev.langchain4j.agentic.planner.PlanningContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.decision.DecisionModel;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.AiMessage;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.function.Function;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/** Keeps the chat interface stable while routing to independently relevant experts. */
public final class ExpertAssistantWorkflow {
    static final List<String> EXPERT_NAMES = List.of("医疗专家", "法律专家", "技术专家");

    public interface MultiExpertRouter {
        @Agent(name = "多专家路由")
        Map<String, String> route(@MemoryId String memoryId, @V("request") String request,
                                 @V("routingContext") List<Map<String, String>> routingContext,
                                 InvocationParameters parameters);
    }

    public interface ResponseSynthesizer {
        @UserMessage("""
                综合专家回答：根据用户请求整合以下专家的回答，输出一份清晰、连贯的答复。
                合并重复内容，保留重要限制。专家意见冲突时明确说明，不擅自判断哪方正确。
                只使用提供的专家结论，不补造结论，不声称专家选择概率是答案正确率。
                用户请求：{{request}}
                近期上下文（仅用于解释本轮请求的指代）：{{routingContext}}
                专家回答：{{expertResponses}}
                未完成的专家：{{expertFailures}}。明确说明缺失的视角，不补造其结论。
                """)
        @Agent(name = "专家回答汇总", outputKey = "response")
        String synthesize(@V("request") String request,
                          @V("expertResponses") Map<String, String> expertResponses,
                          @V("expertFailures") Map<String, String> expertFailures,
                          @V("routingContext") List<Map<String, String>> routingContext);
    }

    private ExpertAssistantWorkflow() {}

    public static ExportAssistant build(DecisionModel decisionModel, ChatModel chatModel,
                                        AgentListener listener, double threshold, Object... experts) {
        return build(decisionModel, chatModel, listener, threshold, ignored -> List.of(), experts);
    }

    public static ExportAssistant build(DecisionModel decisionModel, ChatModel chatModel,
                                        AgentListener listener, double threshold,
                                        Function<Object, List<ChatMessage>> history, Object... experts) {
        if (!Double.isFinite(threshold) || threshold <= 0 || threshold >= 1) {
            throw new IllegalArgumentException("Expert activation threshold must be strictly between 0 and 1");
        }
        ExpertRoutingDecisionModel routing = new ExpertRoutingDecisionModel(decisionModel, chatModel);
        MultiExpertRouter router = AgenticServices.plannerBuilder(MultiExpertRouter.class)
                .subAgents(experts)
                .outputKey("expertResponses")
                .listener(listener)
                .planner(() -> recordingPlanner(routing, threshold))
                .build();
        ResponseSynthesizer synthesizer = AiServices.create(ResponseSynthesizer.class, chatModel);
        var response = AgenticServices.nonAiAgentBuilder(scope -> {
                    @SuppressWarnings("unchecked")
                    Map<String, String> answers = (Map<String, String>) scope.readState("expertResponses");
                    if (answers.isEmpty()) {
                        scope.writeState("synthesisSource", "clarification");
                        return "当前专家助手支持医疗、法律和技术问题，请补充相关问题或说明需要哪方面的帮助。";
                    }
                    Map<String, String> successful = new LinkedHashMap<>();
                    Map<String, String> failed = new LinkedHashMap<>(failures(scope));
                    for (String name : EXPERT_NAMES) {
                        if (!answers.containsKey(name)) continue;
                        String answer = answers.get(name);
                        if (answer == null || answer.isBlank()) {
                            failed.putIfAbsent(name, "未返回有效回答");
                        } else if (!failed.containsKey(name)) {
                            successful.put(name, answer);
                        }
                    }
                    if (successful.isEmpty()) {
                        throw new IllegalStateException("已激活专家均未返回有效回答");
                    }
                    Map<String, String> orderedFailures = new LinkedHashMap<>();
                    EXPERT_NAMES.stream().filter(failed::containsKey)
                            .forEach(name -> orderedFailures.put(name, failed.get(name)));
                    scope.writeState("expertResponses", successful);
                    scope.writeState("expertFailures", orderedFailures);
                    String result;
                    try {
                        result = synthesizer.synthesize((String) scope.readState("request"), successful, orderedFailures,
                                routingContext(scope));
                        if (result == null || result.isBlank()) {
                            throw new RuntimeException("汇总未返回有效回答");
                        }
                        scope.writeState("synthesisSource", "llm");
                    } catch (RuntimeException failure) {
                        if (!ExpertFailurePolicy.recoverable(failure)) throw failure;
                        scope.writeState("synthesisSource", "expert_answers_fallback");
                        StringBuilder fallback = new StringBuilder("未完成综合整理，以下为已完成专家的原始意见：");
                        successful.forEach((name, answer) -> fallback.append("\n\n").append(name)
                                .append("：\n").append(answer));
                        result = fallback.toString();
                    }
                    return orderedFailures.isEmpty() ? result
                            : result + "\n\n未完成的专家：" + String.join("、", orderedFailures.keySet())
                            + "。以上回答仅包含已完成专家的意见。";
                })
                .name("专家回答汇总")
                .inputs(new AgentArgument(String.class, "request"),
                        new AgentArgument(Map.class, "expertResponses"),
                        new AgentArgument(Map.class, "expertFailures"),
                        new AgentArgument(String.class, "routingSource"),
                        new AgentArgument(Map.class, "routingProbabilities"),
                        new AgentArgument(Double.class, "routingThreshold"))
                .outputType(String.class).outputKey("response").listener(listener).build();
        return AgenticServices.sequenceBuilder(ExportAssistant.class)
                .beforeCall(scope -> {
                    scope.writeState("expertFailures", new ConcurrentHashMap<String, String>());
                    scope.writeState("routingContext", recentConversation(history.apply(scope.memoryId())));
                })
                .errorHandler(error -> {
                    if (!EXPERT_NAMES.contains(error.agentName())
                            || !ExpertFailurePolicy.recoverable(error.exception())) {
                        return ErrorRecoveryResult.throwException();
                    }
                    failures(error.agenticScope()).put(error.agentName(), "服务调用失败");
                    return ErrorRecoveryResult.result(null);
                })
                .subAgents(router, response)
                .listener(listener).outputKey("response").build();
    }

    private static List<Map<String, String>> recentConversation(List<ChatMessage> history) {
        if (history == null || history.isEmpty()) return List.of();
        List<Map<String, String>> context = new ArrayList<>();
        for (int i = history.size() - 1; i >= 0 && context.size() < 6; i--) {
            ChatMessage message = history.get(i);
            String role;
            String text;
            if (message instanceof dev.langchain4j.data.message.UserMessage user && user.hasSingleText()) {
                role = "user";
                text = user.singleText();
            } else if (message instanceof AiMessage ai && !ai.hasToolExecutionRequests()) {
                role = "assistant";
                text = ai.text();
            } else {
                continue;
            }
            if (text == null || text.isBlank()) continue;
            context.add(Map.of("role", role, "text", text.substring(0, Math.min(text.length(), 1000))));
        }
        Collections.reverse(context);
        return List.copyOf(context);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, String>> routingContext(AgenticScope scope) {
        return (List<Map<String, String>>) scope.readState("routingContext");
    }

    private static Planner recordingPlanner(ExpertRoutingDecisionModel routing, double threshold) {
        AtomicReference<ExpertRoutingDecisionModel.Outcome> outcome = new AtomicReference<>();
        DecisionModel recording = request -> {
            var result = routing.route(request);
            outcome.set(result);
            return result.response();
        };
        return new TopologyAwareDecisionRouterPlanner(recording, threshold) {
            @Override
            public Action firstAction(PlanningContext context) {
                Action action = super.firstAction(context);
                var result = outcome.get();
                if (result != null) {
                    context.agenticScope().writeState("routingSource", result.source());
                    context.agenticScope().writeState("routingProbabilities", result.probabilities());
                    context.agenticScope().writeState("routingThreshold", threshold);
                }
                return action;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> failures(AgenticScope scope) {
        return (Map<String, String>) scope.readState("expertFailures");
    }
}
