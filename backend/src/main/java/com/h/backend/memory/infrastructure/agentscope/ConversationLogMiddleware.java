package com.h.backend.memory.infrastructure.agentscope;

import io.agentscope.core.agent.*;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.middleware.AgentInput;
import io.agentscope.harness.agent.middleware.HarnessRuntimeMiddleware;
import io.agentscope.harness.agent.memory.MemoryFlushManager;
import io.agentscope.harness.agent.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.*;
import reactor.core.scheduler.Schedulers;
import java.util.List;
import java.util.function.Function;

/** Log persistence remains enabled without invoking memory extraction or consolidation. */
public final class ConversationLogMiddleware implements HarnessRuntimeMiddleware {
    private static final Logger log=LoggerFactory.getLogger(ConversationLogMiddleware.class);
    @Override public Mono<String> onSystemPrompt(Agent agent,RuntimeContext ctx,String prompt) {
        return new Mem0InstructionsMiddleware().onSystemPrompt(agent, ctx, prompt);
    }
    @Override public Flux<AgentEvent> onAgent(Agent agent,RuntimeContext ctx,AgentInput input,Function<AgentInput,Flux<AgentEvent>> next) {
        return next.apply(input)
                .concatWith(offload(agent,ctx).then(Mono.<AgentEvent>empty()))
                .onErrorResume(error -> offload(agent,ctx).thenMany(Flux.error(error)))
                .doFinally(signal -> {
                    if(signal == SignalType.CANCEL) offload(agent,ctx).subscribe();
                });
    }
    private Mono<Void> offload(Agent agent,RuntimeContext ctx) {
        return Mono.defer(() -> {
            var state=RuntimeContext.resolveAgentState(ctx,agent);
            WorkspaceManager workspace=ctx==null?null:ctx.get(WorkspaceManager.class);
            if(state==null || workspace==null) return Mono.<Void>empty();
            var messages=List.copyOf(state.getContext());
            if(messages.isEmpty()) return Mono.<Void>empty();
            return Mono.<Void>fromRunnable(() -> new MemoryFlushManager(workspace,null)
                    .offloadMessages(ctx,messages,agent.getName(),ctx.getSessionId()));
        }).subscribeOn(Schedulers.boundedElastic()).onErrorResume(error -> {
            log.warn("Conversation log offload failed type={}",error.getClass().getSimpleName());
            return Mono.empty();
        });
    }
}
