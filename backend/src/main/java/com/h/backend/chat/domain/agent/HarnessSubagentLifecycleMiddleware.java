package com.h.backend.chat.domain.agent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEndEvent;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.AgentStartEvent;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.core.middleware.MiddlewareBase;
import io.agentscope.core.middleware.ReasoningInput;
import io.agentscope.core.message.MsgRole;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * 子 Agent 自己拥有的持久化完成边界。
 *
 * <p>父事件流在同步超时后会先结束转发，而子 Agent 仍在后台继续执行；因此完成投影不能只
 * 依赖父流观察到 RESULT/END。该 middleware 随子 Agent 一起运行，在 AgentState 保存完成后
 * 立即通知产品侧投影，正常路径无需等用户再次打开页面。模型增量与工具执行事件分别在
 * reasoning/acting 边界发布，因为父 Agent 通过 {@code call()} 调用子 Agent 时，这些事件会
 * 直接进入父 emitter，不会重新经过子 Agent 的外层 {@code onAgent} Flux。</p>
 */
public final class HarnessSubagentLifecycleMiddleware implements MiddlewareBase {

    @FunctionalInterface
    public interface AssignmentListener {
        void onStarted(String userId, String sessionId, String assignment);
    }

    @FunctionalInterface
    public interface CompletionListener {
        void onCompleted(String userId, String sessionId, String assignment, String content);
    }

    @FunctionalInterface
    public interface EventListener {
        void onEvent(String userId, String sessionId, AgentEvent event);
    }

    private final AssignmentListener assignmentListener;
    private final CompletionListener completionListener;
    private final EventListener eventListener;

    public HarnessSubagentLifecycleMiddleware(CompletionListener completionListener) {
        this((userId, sessionId, assignment) -> { }, completionListener,
                (userId, sessionId, event) -> { });
    }

    public HarnessSubagentLifecycleMiddleware(
            AssignmentListener assignmentListener,
            CompletionListener completionListener
    ) {
        this(assignmentListener, completionListener, (userId, sessionId, event) -> { });
    }

    public HarnessSubagentLifecycleMiddleware(
            AssignmentListener assignmentListener,
            CompletionListener completionListener,
            EventListener eventListener
    ) {
        this.assignmentListener = assignmentListener;
        this.completionListener = completionListener;
        this.eventListener = eventListener;
    }

    @Override
    public Flux<AgentEvent> onAgent(
            Agent agent,
            RuntimeContext context,
            AgentInput input,
            Function<AgentInput, Flux<AgentEvent>> next
    ) {
        String userId = context == null ? null : context.getUserId();
        String sessionId = context == null ? null : context.getSessionId();
        String assignment = input.msgs().stream()
                .filter(message -> message.getRole() == MsgRole.USER)
                .map(message -> message.getTextContent())
                .filter(text -> text != null && !text.isBlank())
                .findFirst()
                .orElse(null);
        // onAgent 早于 AgentState 绑定；先把输入放入当前调用上下文，system-prompt middleware
        // 会在状态绑定后仅对首轮执行委托写入。后续 USER 输入不会覆盖父委托。
        ParentAssignmentSystemPromptMiddleware.stageCurrentInput(context, assignment);
        // 这是子 Agent 真正收到输入的生命周期边界，不依赖父工具何时发送 TOOL_CALL_END。
        assignmentListener.onStarted(userId, sessionId, assignment);
        return next.apply(input).doOnNext(event -> {
            // reasoning/acting 事件已在它们各自的边界发布。直接打开子会话时，
            // 同一批事件还会进入外层 onAgent Flux，因此这里只发布生命周期事件以避免重复。
            if (isAgentLifecycleEvent(event)) {
                eventListener.onEvent(userId, sessionId, event);
            }
            if (!(event instanceof AgentResultEvent resultEvent)
                    || resultEvent.getResult() == null
                    || resultEvent.getResult().getTextContent() == null
                    || resultEvent.getResult().getTextContent().isBlank()) {
                return;
            }
            completionListener.onCompleted(
                    userId,
                    sessionId,
                    assignment,
                    resultEvent.getResult().getTextContent()
            );
        });
    }

    @Override
    public Flux<AgentEvent> onReasoning(
            Agent agent,
            RuntimeContext context,
            ReasoningInput input,
            Function<ReasoningInput, Flux<AgentEvent>> next
    ) {
        return relay(context, next.apply(input));
    }

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext context,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next
    ) {
        return relay(context, next.apply(input));
    }

    private Flux<AgentEvent> relay(RuntimeContext context, Flux<AgentEvent> events) {
        String userId = context == null ? null : context.getUserId();
        String sessionId = context == null ? null : context.getSessionId();
        return events.doOnNext(event -> eventListener.onEvent(userId, sessionId, event));
    }

    private static boolean isAgentLifecycleEvent(AgentEvent event) {
        return event instanceof AgentStartEvent
                || event instanceof AgentResultEvent
                || event instanceof AgentEndEvent;
    }
}
