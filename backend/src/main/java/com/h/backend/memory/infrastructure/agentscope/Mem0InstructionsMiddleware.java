package com.h.backend.memory.infrastructure.agentscope;

import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import reactor.core.publisher.Mono;

/** Only adds tool guidance. Native Harness hooks retain ownership of memory and logging. */
public final class Mem0InstructionsMiddleware implements HarnessRuntimeMiddleware {
    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        return Mono.just(com.h.backend.chat.infrastructure.ai.HarnessCapabilityInstructions.append(
                agent, context, prompt, "memory"));
    }
}
