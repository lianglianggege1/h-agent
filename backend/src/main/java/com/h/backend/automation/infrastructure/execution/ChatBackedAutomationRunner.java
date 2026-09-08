package com.h.backend.automation.infrastructure.execution;

import com.h.backend.automation.application.AutomationExecutionAdapter.AutomationExecutionResult;
import com.h.backend.automation.domain.ExecutionSpec;
import com.h.backend.chat.application.ChatService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.domain.approval.ApprovalMode;
import com.h.backend.chat.interfaces.dto.ChatSessionOpenDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把一次自动化执行投影成独立聊天会话，因此结果、工具产物、Agent run 与观测链路
 * 都复用现有产品语义；调用者只需关心最终 sessionId 与文本结果。
 */
@Component
public class ChatBackedAutomationRunner {

    private final ChatSessionService chatSessionService;
    private final ChatService chatService;
    private final AutomationProperties properties;
    private final AutomationExecutionSessionRegistry executionSessionRegistry;

    public ChatBackedAutomationRunner(
            ChatSessionService chatSessionService,
            ChatService chatService,
            AutomationProperties properties,
            AutomationExecutionSessionRegistry executionSessionRegistry
    ) {
        this.chatSessionService = chatSessionService;
        this.chatService = chatService;
        this.properties = properties;
        this.executionSessionRegistry = executionSessionRegistry;
    }

    public AutomationExecutionResult run(ExecutionSpec spec, ApprovalMode approvalMode) {
        ChatSessionOpenDto opened = chatSessionService.createSession(
                spec.userId(), null, spec.agentId(), approvalMode, null
        );
        String sessionId = opened.session().sessionId();
        Long promptId = opened.session().promptId();
        StringBuilder chunks = new StringBuilder();
        AtomicReference<String> finalOutput = new AtomicReference<>();
        AtomicReference<String> terminalError = new AtomicReference<>();

        try (AutomationExecutionSessionRegistry.Registration ignored =
                     executionSessionRegistry.register(sessionId)) {
            chatService.streamChat(
                            spec.userId(), promptId, spec.agentId(), sessionId,
                            spec.instruction(), List.of()
                    )
                    .doOnNext(event -> capture(event, chunks, finalOutput, terminalError))
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

    private static void capture(
            ChatStreamEvent event,
            StringBuilder chunks,
            AtomicReference<String> finalOutput,
            AtomicReference<String> terminalError
    ) {
        if ("chunk".equals(event.type()) && event.content() != null) {
            chunks.append(event.content());
        }
        if ("done".equals(event.type()) && event.message() != null) {
            finalOutput.set(event.message().content());
        }
        if ("blocked".equals(event.type()) || "action_required".equals(event.type())) {
            String detail = event.content() == null || event.content().isBlank()
                    ? "，已终止" : "：" + event.content();
            terminalError.set(REQUIRES_HUMAN_PREFIX + detail);
        } else if ("error".equals(event.type())) {
            terminalError.set(event.content() == null || event.content().isBlank()
                    ? "Agent 执行失败" : event.content());
        }
    }

    private static boolean terminal(ChatStreamEvent event) {
        return switch (event.type()) {
            case "done", "error", "blocked", "action_required" -> true;
            default -> false;
        };
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
