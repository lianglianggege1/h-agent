package com.h.backend.memory.infrastructure.agentscope;

import com.h.backend.memory.application.MemoryToolContract;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import reactor.core.publisher.Mono;

/** Only adds tool guidance. Native Harness hooks retain ownership of memory and logging. */
public final class Mem0InstructionsMiddleware implements HarnessRuntimeMiddleware {
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        boolean hasMem0 = agent instanceof ReActAgent react
                && react.getToolkit().getTool("mem0_search") != null;
        return Mono.just(hasMem0 ? prompt + "\n\n" + MemoryToolContract.harnessInstructions() : prompt);
    }
}
