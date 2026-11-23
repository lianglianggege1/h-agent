package com.h.backend.chat.domain.agent;

import dev.langchain4j.agentic.planner.AgentInstance;

/** Exposes the executable LangChain4j workflow behind a chat runtime adapter. */
public interface AgentTopologyProvider {
    AgentInstance executionGraph();
}
