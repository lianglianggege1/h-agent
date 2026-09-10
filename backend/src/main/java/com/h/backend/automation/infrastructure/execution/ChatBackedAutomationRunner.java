package com.h.backend.automation.infrastructure.execution;

import com.h.backend.automation.application.AutomationExecutionAdapter.AutomationExecutionResult;
import com.h.backend.automation.domain.ExecutionSpec;
import com.h.backend.chat.application.ChatService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.domain.model.AgentRunSummary;
import com.h.backend.chat.interfaces.dto.ApprovalRequestDto;
import com.h.backend.chat.interfaces.dto.ChatSessionOpenDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 在任务绑定的原聊天会话中执行，使上下文、工具和批准模式与用户创建会话时完全一致。
 */
@Component
public class ChatBackedAutomationRunner {

    private final ChatSessionService chatSessionService;
    private final ChatService chatService;
    private final AutomationProperties properties;
    private final AgentRunService agentRunService;

    public ChatBackedAutomationRunner(
            ChatSessionService chatSessionService,
            ChatService chatService,
            AutomationProperties properties,
            AgentRunService agentRunService
    ) {
        this.chatSessionService = chatSessionService;
        this.chatService = chatService;
        this.properties = properties;
        this.agentRunService = agentRunService;
    }

    public AutomationExecutionResult run(ExecutionSpec spec) {
        ChatSessionOpenDto opened = chatSessionService.activateHistorySession(
                spec.userId(), spec.sessionId(), null);
        var session = opened.session();
        if (!spec.agentId().equals(session.agentId())) {
            throw new AutomationPolicyRejectionException("自动化任务绑定的 Agent 与会话不一致");
        }
        String sessionId = session.sessionId();
        StringBuilder chunks = new StringBuilder();
        AtomicReference<String> finalOutput = new AtomicReference<>();
        AtomicReference<String> terminalError = new AtomicReference<>();
        AtomicReference<Long> approvalRunId = new AtomicReference<>();
        long deadlineNanos = System.nanoTime() + properties.getExecutionTimeout().toNanos();

        try {
            chatService.streamChat(
                            spec.userId(), session.promptId(), session.agentId(), sessionId,
                            automationPrompt(spec), List.of()
                    )
                    .doOnNext(event -> capture(event, chunks, finalOutput, terminalError, approvalRunId))
                    .takeUntil(ChatBackedAutomationRunner::terminal)
                    .timeout(properties.getExecutionTimeout())
                    .blockLast();
        } catch (RuntimeException error) {
            if (hasCause(error, TimeoutException.class) || isBlockingTimeout(error)) {
                throw new AutomationExecutionTimeoutException(
                        "自动化任务执行超过 " + properties.getExecutionTimeout().toMinutes() + " 分钟，已终止", error);
            }
            throw error;
        }

        if (approvalRunId.get() != null) {
            finalOutput.set(awaitApprovalCompletion(spec, sessionId, approvalRunId.get(), deadlineNanos));
        }

        if (terminalError.get() != null) {
            String reason = terminalError.get();
            if (reason.startsWith(REQUIRES_HUMAN_PREFIX)) {
                throw new AutomationPolicyRejectionException(reason);
            }
            throw new IllegalStateException(reason);
        }
        String output = finalOutput.get();
        if (output == null || output.isBlank()) {
            output = chunks.toString();
        }
        if (output.isBlank()) {
            throw new IllegalStateException("Agent 未返回有效内容");
        }
        return new AutomationExecutionResult(sessionId, output);
    }

    private static final String REQUIRES_HUMAN_PREFIX = "自动化执行需要人工处理";

    private static String automationPrompt(ExecutionSpec spec) {
        return "【自动化任务：" + spec.taskName() + "，Run：" + spec.runId() + "】\n"
                + spec.instruction();
    }

    private static void capture(
            ChatStreamEvent event,
            StringBuilder chunks,
            AtomicReference<String> finalOutput,
            AtomicReference<String> terminalError,
            AtomicReference<Long> approvalRunId
    ) {
        if ("chunk".equals(event.type()) && event.content() != null) {
            chunks.append(event.content());
        }
        if ("done".equals(event.type()) && event.message() != null) {
            finalOutput.set(event.message().content());
        }
        if ("action_required".equals(event.type())) {
            if (event.payload() instanceof ApprovalRequestDto request) {
                // The normal chat approval card owns the continuation. Keep the XXL
                // handler waiting for that AgentRun instead of rejecting the run here.
                approvalRunId.set(request.runId());
            } else {
                terminalError.set(REQUIRES_HUMAN_PREFIX + detail(event));
            }
        } else if ("blocked".equals(event.type())) {
            terminalError.set(REQUIRES_HUMAN_PREFIX + detail(event));
        } else if ("error".equals(event.type())) {
            terminalError.set(event.content() == null || event.content().isBlank()
                    ? "Agent 执行失败" : event.content());
        }
    }

    private static String detail(ChatStreamEvent event) {
        return event.content() == null || event.content().isBlank()
                ? "，已终止" : "：" + event.content();
    }

    private static boolean terminal(ChatStreamEvent event) {
        return switch (event.type()) {
            case "done", "error", "blocked", "action_required" -> true;
            default -> false;
        };
    }

    private String awaitApprovalCompletion(
            ExecutionSpec spec,
            String sessionId,
            Long agentRunId,
            long deadlineNanos
    ) {
        while (System.nanoTime() < deadlineNanos) {
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("自动化执行已取消");
            }
            AgentRunSummary run = agentRunService.getById(agentRunId);
            if ("SUCCEEDED".equals(run.status())) {
                if (run.assistantMessageId() == null) {
                    throw new IllegalStateException("Agent 已结束但没有结果消息");
                }
                return chatSessionService.getOwnedMessage(
                        spec.userId(), sessionId, run.assistantMessageId()).content();
            }
            if ("FAILED".equals(run.status()) || "CANCELLED".equals(run.status())) {
                throw new IllegalStateException(run.errorMessage() == null
                        ? "Agent 执行失败" : run.errorMessage());
            }
            try {
                Thread.sleep(250);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("自动化执行已取消", error);
            }
        }
        throw new AutomationExecutionTimeoutException(
                "自动化任务等待会话批准超过 " + properties.getExecutionTimeout().toMinutes() + " 分钟，已终止",
                null);
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> causeType) {
        Throwable current = error;
        while (current != null) {
            if (causeType.isInstance(current)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static boolean isBlockingTimeout(RuntimeException error) {
        return error.getMessage() != null && error.getMessage().contains("Timeout on blocking read");
    }
}
