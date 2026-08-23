package com.h.backend.chat.infrastructure.subagent;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.middleware.ActingInput;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

/**
 * label guard（设计 8.2 第 2 条）：携带非空 label 的 agent_spawn 调用被重写为
 * 哨兵 agent_id（必然产生可见工具错误），其余调用原样透传。
 */
class SubagentSpawnGuardMiddlewareTest {

    private final SubagentSpawnGuardMiddleware middleware = new SubagentSpawnGuardMiddleware();

    @Test
    void rewritesSpawnWithNonBlankLabel() {
        ToolUseBlock violating = new ToolUseBlock("call-1", "agent_spawn", Map.of(
                "agent_id", "my-reviewer",
                "task", "审查这段代码",
                "label", "my-label"
        ));
        List<ToolUseBlock> passed = apply(List.of(violating));

        assertEquals(1, passed.size());
        ToolUseBlock rewritten = passed.getFirst();
        // 保留原调用 id：tool_use/tool_result 配对不受影响。
        assertEquals("call-1", rewritten.getId());
        assertEquals("agent_spawn", rewritten.getName());
        assertEquals(
                SubagentSpawnGuardMiddleware.LABEL_DENIED_SENTINEL_AGENT_ID,
                rewritten.getInput().get("agent_id")
        );
        assertNull(rewritten.getInput().get("label"));
        assertEquals("审查这段代码", rewritten.getInput().get("task"));
    }

    @Test
    void passesThroughLabelOmittedOrBlank() {
        ToolUseBlock omitted = new ToolUseBlock("call-1", "agent_spawn", Map.of(
                "agent_id", "my-reviewer", "task", "t"));
        ToolUseBlock blank = new ToolUseBlock("call-2", "agent_spawn", Map.of(
                "agent_id", "my-reviewer", "label", "  "));
        ToolUseBlock other = new ToolUseBlock("call-3", "task_output", Map.of("task_id", "t1"));

        List<ToolUseBlock> input = List.of(omitted, blank, other);
        assertEquals(input, apply(input));
    }

    @Test
    void onlyTouchesViolatingCallsInBatch() {
        ToolUseBlock normal = new ToolUseBlock("call-1", "agent_spawn", Map.of(
                "agent_id", "researcher", "task", "找资料"));
        ToolUseBlock violating = new ToolUseBlock("call-2", "agent_spawn", Map.of(
                "agent_id", "researcher", "label", "x"));
        List<ToolUseBlock> passed = apply(List.of(normal, violating));

        assertEquals(2, passed.size());
        assertEquals("researcher", passed.get(0).getInput().get("agent_id"));
        assertEquals(
                SubagentSpawnGuardMiddleware.LABEL_DENIED_SENTINEL_AGENT_ID,
                passed.get(1).getInput().get("agent_id")
        );
    }

    @Test
    void handlesNullToolCalls() {
        assertEquals(List.of(), apply(List.of()));
    }

    private List<ToolUseBlock> apply(List<ToolUseBlock> toolCalls) {
        List<ToolUseBlock> captured = new java.util.ArrayList<>();
        Function<ActingInput, Flux<AgentEvent>> next = input -> {
            captured.addAll(input.toolCalls());
            return Flux.empty();
        };
        middleware.onActing(mock(Agent.class), RuntimeContext.empty(), new ActingInput(toolCalls), next)
                .then().block();
        return captured;
    }
}
