package com.h.backend.chat.domain.agent;

import com.h.backend.chat.domain.approval.ApprovalMode;
import com.h.backend.chat.domain.approval.ApprovalEpisode;
import io.agentscope.core.ReActAgent;
import io.agentscope.core.event.ConfirmResult;
import io.agentscope.core.event.RequireUserConfirmEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.ToolCallState;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.permission.AdditionalWorkingDirectory;
import io.agentscope.core.permission.PermissionContextState;
import io.agentscope.core.permission.PermissionMode;
import io.agentscope.core.permission.PermissionRule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.HexFormat;

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

    /** 把 SDK 事件降维成不含命令、密钥或原始参数的产品快照。 */
    public ApprovalEpisode capture(RequireUserConfirmEvent event) {
        Objects.requireNonNull(event, "confirmation event");
        List<ApprovalEpisode.ToolCall> calls = event.getToolCalls().stream()
                .map(call -> new ApprovalEpisode.ToolCall(
                        call.getId(),
                        safeToolName(call.getName()),
                        "请求执行 " + safeToolName(call.getName())
                                + "（" + call.getInput().size() + " 个参数，内容已隐藏）"
                ))
                .toList();
        String keyMaterial = (event.getReplyId() == null ? "" : event.getReplyId()) + "\n"
                + calls.stream()
                .map(ApprovalEpisode.ToolCall::id)
                .sorted(Comparator.nullsFirst(String::compareTo))
                .reduce("", (left, right) -> left + "\n" + right);
        return new ApprovalEpisode(sha256(keyMaterial), event.getReplyId(), calls);
    }

    /**
     * 从 AgentScope 已持久化的 ASKING 状态重建确认消息，避免信任数据库中的可执行参数。
     */
    public Msg confirmationMessage(
            ReActAgent agent,
            String userId,
            String sessionId,
            List<String> expectedToolCallIds,
            boolean approved
    ) {
        Set<String> expected = new LinkedHashSet<>(expectedToolCallIds);
        List<ToolUseBlock> asking = agent.getAgentState(userId, sessionId).getContext().stream()
                .filter(message -> message.getRole() == MsgRole.ASSISTANT)
                .reduce((first, second) -> second)
                .stream()
                .flatMap(message -> message.getContentBlocks(ToolUseBlock.class).stream())
                .filter(call -> call.getState() == ToolCallState.ASKING)
                .filter(call -> expected.contains(call.getId()))
                .toList();
        Set<String> recovered = asking.stream()
                .map(ToolUseBlock::getId)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (expected.isEmpty() || !recovered.equals(expected)) {
            throw new IllegalStateException(
                    "待审批工具调用与 Agent 状态不一致，拒绝恢复执行"
            );
        }
        List<ConfirmResult> results = asking.stream()
                .map(call -> new ConfirmResult(approved, call))
                .toList();
        return UserMessage.builder()
                .name("user")
                .metadata(Map.of(Msg.METADATA_CONFIRM_RESULTS, results))
                .build();
    }

    private String safeToolName(String name) {
        if (name == null || name.isBlank()) {
            return "未知工具";
        }
        return name.replaceAll("[^a-zA-Z0-9_.:-]", "_");
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
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
