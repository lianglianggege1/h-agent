package com.h.backend.knowledge.interfaces.tool;

import io.agentscope.core.agent.*;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import reactor.core.publisher.Mono;

public final class KnowledgeInstructionsMiddleware implements HarnessRuntimeMiddleware {
    @Override public Mono<String> onSystemPrompt(Agent agent,RuntimeContext ctx,String prompt) {
        return Mono.just(com.h.backend.chat.infrastructure.ai.HarnessCapabilityInstructions.append(
                agent, ctx, prompt, "knowledge"));
    }
}
