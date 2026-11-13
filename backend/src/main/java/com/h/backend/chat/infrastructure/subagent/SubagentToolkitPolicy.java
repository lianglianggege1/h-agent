package com.h.backend.chat.infrastructure.subagent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.tool.Toolkit;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/** Applies the leaf allowlist to the complete registry, independently of group visibility. */
final class SubagentToolkitPolicy {
    private SubagentToolkitPolicy() {}

    static void retainAllowed(Toolkit toolkit, Collection<String> allowed) {
        Set<String> keep = Set.copyOf(allowed);
        for (String name : Set.copyOf(toolkit.getToolNames())) {
            if (!keep.contains(name)) toolkit.removeTool(name);
        }
    }

    static Set<String> parentDenied(Toolkit parent, Collection<String> requestable,
                                    RuntimeContext context, PermissionContextState fallback) {
        Set<String> denied = new HashSet<>(requestable);
        denied.removeAll(parent.getToolNames());
        PermissionContextState permissions = context != null && context.getAgentState() != null
                ? context.getAgentState().getPermissionContext() : fallback;
        if (permissions != null) {
            // A leaf cannot broaden a parent's conditional DENY either. Conservatively omit
            // the entire named tool rather than dropping its argument-sensitive restriction.
            permissions.getDenyRules().forEach((name, rules) -> {
                if (!rules.isEmpty()) denied.add(name);
            });
        }
        return Set.copyOf(denied);
    }
}
