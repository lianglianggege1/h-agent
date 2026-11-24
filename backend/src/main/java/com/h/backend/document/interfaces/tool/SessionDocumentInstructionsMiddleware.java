package com.h.backend.document.interfaces.tool;

import com.h.backend.document.application.SessionDocumentCanvas;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public final class SessionDocumentInstructionsMiddleware implements HarnessRuntimeMiddleware {
    private final SessionDocumentCanvas canvases;

    public SessionDocumentInstructionsMiddleware(SessionDocumentCanvas canvases) {
        this.canvases = canvases;
    }

    @Override
    public Mono<String> onSystemPrompt(Agent agent, RuntimeContext context, String prompt) {
        if (context == null || context.getUserId() == null || context.getSessionId() == null
                || !context.getUserId().matches("[0-9]+")) {
            return Mono.just(prompt);
        }
        return Mono.fromCallable(() -> prompt + canvases.promptHint(Long.parseLong(context.getUserId()),
                context.getSessionId())).subscribeOn(Schedulers.boundedElastic());
    }
}
