package com.h.backend.chat.domain.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.function.Function;

/** Keeps the persisted parent delegation in the model's actual system prompt. */
public final class ParentAssignmentSystemPromptMiddleware implements MiddlewareBase {

    public static final String MESSAGE_NAME = "parent_assignment";
    static final String CURRENT_INPUT_ATTRIBUTE =
            ParentAssignmentSystemPromptMiddleware.class.getName() + ".current-input";

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        initializeFirstDelegation(context);
        String assignment = assignmentFrom(context);
        if (assignment == null) {
            return Mono.just(prompt);
        }
        return Mono.just(prompt
                + "\n\n## Parent Agent Delegation\n"
                + "This delegation remains authoritative throughout this subagent session.\n\n"
                + assignment);
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext context,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next
    ) {
        // The assignment is already merged into the leading provider SYSTEM message above.
        // Remove its persisted copy only from this model-call view to avoid Anthropic converting
        // a second SYSTEM message into USER; AgentState itself remains unchanged and durable.
        List<Msg> modelMessages = input.messages().stream()
                .filter(message -> !isAssignment(message))
                .toList();
        return next.apply(new ReasoningInput(modelMessages, input.tools(), input.options()));
    }

    static String assignmentFrom(RuntimeContext context) {
        if (context == null || context.getAgentState() == null) {
            return null;
        }
        return context.getAgentState().getContext().stream()
                .filter(ParentAssignmentSystemPromptMiddleware::isAssignment)
                .map(Msg::getTextContent)
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(null);
    }

    /**
     * {@code onAgent} 发生在 AgentScope 把 AgentState 绑定到 RuntimeContext 之前，因此先把
     * 本轮输入暂存在调用上下文；到 {@code onSystemPrompt} 时再判断是否为首轮并写入状态。
     */
    static void stageCurrentInput(RuntimeContext context, String input) {
        if (isSubagentContext(context) && input != null && !input.isBlank()) {
            context.put(CURRENT_INPUT_ATTRIBUTE, input.trim());
        }
    }

    private static void initializeFirstDelegation(RuntimeContext context) {
        if (!isSubagentContext(context) || context.getAgentState() == null) {
            return;
        }
        boolean hasConversationHistory = context.getAgentState().getContext().stream()
                .anyMatch(message -> message.getRole() == MsgRole.USER
                        || message.getRole() == MsgRole.ASSISTANT);
        if (hasConversationHistory) {
            return;
        }
        upsertAssignment(context, context.get(CURRENT_INPUT_ATTRIBUTE));
    }

    private static boolean isSubagentContext(RuntimeContext context) {
        return context != null
                && context.getSessionId() != null
                && context.getSessionId().startsWith("sub-");
    }

    /**
     * 以当前产品 child session 的权威委托修复 AgentState；同名消息只允许一条。
     */
    public static boolean upsertAssignment(RuntimeContext context, String assignment) {
        if (context == null || context.getAgentState() == null
                || assignment == null || assignment.isBlank()) {
            return false;
        }
        String normalized = assignment.trim();
        List<Msg> messages = context.getAgentState().contextMutable();
        int assignmentIndex = -1;
        boolean changed = false;
        for (int index = 0; index < messages.size(); index++) {
            Msg message = messages.get(index);
            if (!isAssignment(message)) continue;
            if (assignmentIndex < 0) {
                assignmentIndex = index;
                if (!normalized.equals(message.getTextContent())) {
                    messages.set(index, assignmentMessage(normalized));
                    changed = true;
                }
                continue;
            }
            messages.remove(index--);
            changed = true;
        }
        if (assignmentIndex >= 0) return changed;
        messages.addFirst(assignmentMessage(normalized));
        return true;
    }

    public static Msg assignmentMessage(String assignment) {
        return Msg.builder()
                .name(MESSAGE_NAME)
                .role(MsgRole.SYSTEM)
                .textContent(assignment)
                .build();
    }

    public static boolean isAssignment(Msg message) {
        return message != null
                && message.getRole() == MsgRole.SYSTEM
                && MESSAGE_NAME.equals(message.getName());
    }
}
