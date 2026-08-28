package com.h.backend.chat.domain.agent;

import com.h.backend.chat.domain.approval.ApprovalMode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** AgentScope 权限模型与项目批准模式之间的唯一适配边界。 */
@Component
public class AgentScopeApprovalAdapter {

    private final String workspaceRoot;

    public AgentScopeApprovalAdapter(
            @Value("${chat.harness.workspace-template:/tmp/h-agent/harness-workspace}")
            String workspaceRoot
    ) {
        this.workspaceRoot = Path.of(workspaceRoot).toAbsolutePath().normalize().toString();
    }

    public void applyMode(
            ReActAgent agent,
            String userId,
            String sessionId,
            ApprovalMode mode
    ) {
        Objects.requireNonNull(agent, "agent");
        Objects.requireNonNull(mode, "approval mode");
        PermissionContextState current =
                agent.getAgentState(userId, sessionId).getPermissionContext();
        PermissionContextState.Builder builder = PermissionContextState.builder().mode(toSdk(mode));
        current.getWorkingDirectories().forEach(builder::addWorkingDirectory);
        copyRules(current.getAllowRules(), builder::addAllowRule);
        copyRules(current.getDenyRules(), builder::addDenyRule);
        copyRules(current.getAskRules(), builder::addAskRule);
        builder.addWorkingDirectory(
                workspaceRoot,
                new AdditionalWorkingDirectory(workspaceRoot, "projectSettings")
        );
        agent.replacePermissionContext(userId, sessionId, builder.build());
    }

    PermissionMode toSdk(ApprovalMode mode) {
        return switch (mode) {
            case DEFAULT -> PermissionMode.DEFAULT;
            case ACCEPT_EDITS -> PermissionMode.ACCEPT_EDITS;
            case EXPLORE -> PermissionMode.EXPLORE;
            case BYPASS -> PermissionMode.BYPASS;
            case DONT_ASK -> PermissionMode.DONT_ASK;
        };
    }

    private void copyRules(
            Map<String, List<PermissionRule>> rules,
            RuleConsumer consumer
    ) {
        rules.forEach((toolName, entries) ->
                entries.forEach(rule -> consumer.add(toolName, rule)));
    }

    @FunctionalInterface
    private interface RuleConsumer {
        void add(String toolName, PermissionRule rule);
    }
}
