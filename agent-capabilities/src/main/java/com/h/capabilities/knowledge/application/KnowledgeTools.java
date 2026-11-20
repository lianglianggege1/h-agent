package com.h.capabilities.knowledge.application;

import com.h.capabilities.memory.application.AgentMemoryContext;
import java.util.Map;

public interface KnowledgeTools {
    String execute(AgentMemoryContext context,String toolCallId,String tool,Map<String,Object> arguments);
}
