package com.h.backend.chat.infrastructure.ai;

import com.h.backend.chat.application.capability.CapabilityCatalog;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.Agent;
import io.agentscope.core.agent.RuntimeContext;

/** Uses the call's active groups, never another session's currently selected toolkit groups. */
public final class HarnessCapabilityInstructions {
    private HarnessCapabilityInstructions() {}

    public static String append(Agent agent, RuntimeContext context, String prompt, String capability) {
        if (!(agent instanceof ReActAgent react)) return prompt;
        var toolkit = react.getToolkit();
        var state = context == null ? null : context.getAgentState();
        var schemas = state == null ? toolkit.getToolSchemas()
                : toolkit.getToolSchemas(state.getToolContext().getActivatedGroups());
        String instructions = CapabilityCatalog.instructions(CapabilityCatalog.Runtime.HARNESS,
                capability, schemas.stream().map(io.agentscope.core.model.ToolSchema::getName).toList());
        return instructions.isBlank() ? prompt : prompt + "\n\n" + instructions;
    }
}
