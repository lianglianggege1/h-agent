package com.h.backend.knowledge.interfaces.tool;

import com.h.backend.knowledge.application.KnowledgeToolContract;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.*;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import reactor.core.publisher.Mono;

public final class KnowledgeInstructionsMiddleware implements HarnessRuntimeMiddleware {
    @Override public Mono<String> onSystemPrompt(Agent agent,RuntimeContext ctx,String prompt) {
        boolean available=agent instanceof ReActAgent react && react.getToolkit().getTool("knowledge_search")!=null;
        return Mono.just(available?prompt+"\n\n"+KnowledgeToolContract.INSTRUCTIONS:prompt);
    }
}
