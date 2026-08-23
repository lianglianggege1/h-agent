package com.h.backend.chat.infrastructure.subagent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import io.agentscope.core.middleware.MiddlewareBase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * {@code agent_spawn} label guard（设计 8.2 第 2 条）。
 *
 * <p>AgentScope 2.0.1 的 {@code AgentSpawnTool} 用共享 {@code labelToKey} Map 登记 label，
 * 该 Map 没有 (userId, parentSessionId) 分桶，跨用户会互相可见。平台第一期不使用
 * SDK 的 label/key 会话管理：父 Toolkit 已 DENY {@code agent_send}/{@code agent_list}，
 * 本 middleware 进一步拒绝携带非空 {@code label} 的 {@code agent_spawn} 调用，
 * 保证共享 Map 不产生任何条目。</p>
 *
 * <p>实现方式：AgentScope 的工具执行按调用隔离错误（ToolExecutor 对单个工具的
 * 异常/未知 agent_id 返回可见的 error ToolResult，不影响同批其他调用），
 * 因此 guard 在 {@code onActing} 把违规调用的 {@code agent_id} 重写为哨兵值——
 * {@code AgentSpawnTool} 对未注册 agent_id 返回
 * {@code "Error: Unknown agent_id: <哨兵>"} 的可见工具错误，模型据此省略 label 重试。
 * 重写保留原工具调用 id，tool_use/tool_result 配对不受影响。</p>
 */
public final class SubagentSpawnGuardMiddleware implements MiddlewareBase {

    private static final Logger log = LoggerFactory.getLogger(SubagentSpawnGuardMiddleware.class);

    /** 哨兵 agent_id：不会与任何真实注册名冲突，文本本身就是重试指引。 */
    static final String LABEL_DENIED_SENTINEL_AGENT_ID =
            "label_denied_by_platform__omit_label_param_and_retry";

    private static final String LABEL_PARAM = "label";

    @Override
    public Flux<AgentEvent> onActing(
            Agent agent,
            RuntimeContext ctx,
            ActingInput input,
            Function<ActingInput, Flux<AgentEvent>> next) {
        List<ToolUseBlock> toolCalls = input != null && input.toolCalls() != null
                ? input.toolCalls()
                : List.of();
        List<ToolUseBlock> rewritten = null;
        for (int i = 0; i < toolCalls.size(); i++) {
            ToolUseBlock call = toolCalls.get(i);
            if (!isViolatingSpawn(call)) {
                continue;
            }
            if (rewritten == null) {
                rewritten = new ArrayList<>(toolCalls);
            }
            rewritten.set(i, denyLabel(call));
            log.warn(
                    "[SubagentSpawnGuard] 拒绝携带 label 的 agent_spawn 调用："
                            + "userId={} sessionId={} label={} toolCallId={}",
                    ctx != null ? ctx.getUserId() : null,
                    ctx != null ? ctx.getSessionId() : null,
                    call.getInput().get(LABEL_PARAM),
                    call.getId()
            );
        }
        if (rewritten == null) {
            return next.apply(input);
        }
        return next.apply(new ActingInput(List.copyOf(rewritten)));
    }

    private static boolean isViolatingSpawn(ToolUseBlock call) {
        if (call == null || !SubagentToolNames.AGENT_SPAWN.equals(call.getName())) {
            return false;
        }
        Map<String, Object> callInput = call.getInput();
        Object label = callInput == null ? null : callInput.get(LABEL_PARAM);
        return label != null && !String.valueOf(label).isBlank();
    }

    /**
     * 重写为必然失败的调用：agent_id 指向哨兵值并移除 label。
     * AgentSpawnTool 返回可见错误文本，本次 spawn 不发生任何物化。
     */
    private static ToolUseBlock denyLabel(ToolUseBlock call) {
        Map<String, Object> denied = new java.util.LinkedHashMap<>();
        Object task = call.getInput().get("task");
        if (task != null) {
            denied.put("task", task);
        }
        denied.put("agent_id", LABEL_DENIED_SENTINEL_AGENT_ID);
        return new ToolUseBlock(call.getId(), call.getName(), Map.copyOf(denied));
    }
}
