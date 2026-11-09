package com.h.backend.memory.application;

import java.util.Map;

/** The shared memory interface used by framework tools. */
public interface AgentMemory {
    String execute(AgentMemoryContext context, String toolCallId, String name, Map<String,Object> arguments);
}
