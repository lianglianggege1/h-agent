package com.h.otheragents.team.capability;

import com.h.matrixteam.TeamProgressReporter;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

import java.util.UUID;

/**
 * 记录工具执行步骤的 ToolCallback 包装：开始即上报步骤并清空此前累积的正文
 * （中间轮文本不是最终回答），结束回填结果。reporter 为 null 时纯透传。
 */
public final class RecordingToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final TeamProgressReporter reporter;
    private final Runnable onToolStart;

    public RecordingToolCallback(ToolCallback delegate, TeamProgressReporter reporter, Runnable onToolStart) {
        this.delegate = delegate;
        this.reporter = reporter;
        this.onToolStart = onToolStart;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        return record(toolInput, null);
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        return record(toolInput, toolContext);
    }

    private String record(String toolInput, ToolContext toolContext) {
        if (reporter == null) {
            return toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
        }
        String id = "call-" + UUID.randomUUID().toString().substring(0, 8);
        onToolStart.run();
        reporter.toolStart(id, delegate.getToolDefinition().name());
        try {
            String result = toolContext == null ? delegate.call(toolInput) : delegate.call(toolInput, toolContext);
            reporter.toolResult(id, result, false);
            return result;
        } catch (RuntimeException failure) {
            reporter.toolResult(id, failure.getMessage(), true);
            throw failure;
        }
    }
}