package com.h.backend.memory.infrastructure;

import com.h.backend.memory.application.AgentMemoryContext;
import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.infrastructure.persistence.entity.AgentSessionEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.AgentSessionMapper;
import io.agentscope.core.agent.RuntimeContext;
import org.springframework.stereotype.Component;

/** Resolve the actual child session; never reuse the parent's agent scope in a child. */
@Component
public class MemoryExecutionContextResolver {
    private final AgentSessionMapper sessions;
    private final com.h.backend.chat.application.ChatSessionService chats;
    public MemoryExecutionContextResolver(AgentSessionMapper sessions,
            @org.springframework.context.annotation.Lazy com.h.backend.chat.application.ChatSessionService chats) {
        this.sessions = sessions; this.chats = chats;
    }
    private String childExecutionId(RuntimeContext ctx, AgentMemoryContext base) {
        String staged = com.h.backend.chat.domain.agent.HarnessSubagentLifecycleMiddleware.executionIdFrom(ctx);
        if (staged != null) return staged;
        if (base != null) return base.executionId();
        String value = ctx.get("h-agent.memory-execution-id");
        if (value == null) {
            value = java.util.UUID.randomUUID().toString();
            ctx.put("h-agent.memory-execution-id", value);
        }
        return value;
    }
    private Long sourceMessage(Long user,String session) {
        var page = chats.getSessionMessages(user,session,20,null);
        var messages = page.messages();
        for(int i=messages.size()-1;i>=0;i--) {
            var message=messages.get(i);
            if ("USER".equalsIgnoreCase(message.role()) || "SYSTEM".equalsIgnoreCase(message.role()))
                return Long.valueOf(message.id());
        }
        return null;
    }
    public AgentMemoryContext resolve(RuntimeContext ctx) {
        if (ctx == null || ctx.getUserId() == null || ctx.getSessionId() == null)
            throw new IllegalArgumentException("Missing trusted execution identity");
        Long user = Long.valueOf(ctx.getUserId());
        AgentSessionEntity actual = sessions.selectBySessionId(ctx.getSessionId());
        if (actual == null || !user.equals(actual.getUserId())) throw new IllegalArgumentException("Session unavailable");
        AgentSessionEntity root = actual;
        int hops = 0;
        while (root.getParentSessionId() != null) {
            if (++hops > 64) throw new IllegalArgumentException("Invalid session lineage");
            root = sessions.selectBySessionId(root.getParentSessionId());
            if (root == null || !user.equals(root.getUserId())) throw new IllegalArgumentException("Session unavailable");
        }
        if (!ChatAgentIds.HARNESS.equals(root.getAgentId())) throw new IllegalArgumentException("Memory tools unavailable for this agent");
        AgentMemoryContext base = ctx.get(AgentMemoryContext.class);
        boolean own = base != null && base.sessionId().equals(ctx.getSessionId()) && user.equals(base.userId());
        String stable = actual.getParentSessionId() == null || actual.getAgentDefinitionId() != null ? actual.getAgentId() : null;
        return new AgentMemoryContext(user, root.getSessionId(), actual.getSessionId(), stable,
                actual.getAgentId(), own ? base.sourceMessageId() : sourceMessage(user,actual.getSessionId()),
                own ? base.executionId() : childExecutionId(ctx, base));
    }
}
