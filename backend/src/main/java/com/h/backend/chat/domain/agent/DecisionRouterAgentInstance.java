package com.h.backend.chat.domain.agent;

import dev.langchain4j.agentic.planner.AgentInstance;

/** Static decision-routing configuration, exposed through AgentInstance.as(). */
public interface DecisionRouterAgentInstance extends AgentInstance {

    enum SelectionMode { SINGLE, MULTIPLE }

    /** Null means single choice; otherwise every agent reaching this threshold is selected. */
    Double activationThreshold();

    default SelectionMode selectionMode() {
        return activationThreshold() == null ? SelectionMode.SINGLE : SelectionMode.MULTIPLE;
    }
}
