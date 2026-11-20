package com.h.otheragents.team.capability;

import com.h.matrixteam.TeamProgressContext;
import com.h.matrixteam.TeamProgressReporter;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;

/**
 * 把每轮模型调用映射为 AG-UI 过程快照：请求里的工具结果回填上一步骤，
 * 响应里的 thinking 记为思考、工具请求记为新步骤。仅在团队执行上下文内记录。
 */
public final class RecordingChatModel implements ChatModel {

    private final ChatModel delegate;

    public RecordingChatModel(ChatModel delegate) {
        this.delegate = delegate;
    }

    @Override
    public ChatResponse chat(ChatRequest request) {
        TeamProgressReporter reporter = TeamProgressContext.get();
        if (reporter != null) {
            for (ChatMessage message : request.messages()) {
                if (message instanceof ToolExecutionResultMessage result) {
                    reporter.toolResult(result.id(), result.text(), Boolean.TRUE.equals(result.isError()));
                }
            }
        }
        ChatResponse response = delegate.chat(request);
        if (reporter != null && response != null && response.aiMessage() != null) {
            AiMessage ai = response.aiMessage();
            if (ai.thinking() != null && !ai.thinking().isBlank()) {
                reporter.reasoning(ai.thinking());
            }
            if (ai.hasToolExecutionRequests()) {
                for (ToolExecutionRequest tool : ai.toolExecutionRequests()) {
                    reporter.toolStart(tool.id(), tool.name());
                }
            } else if (ai.text() != null && !ai.text().isBlank()) {
                reporter.answerStarted();
            }
        }
        return response;
    }
}