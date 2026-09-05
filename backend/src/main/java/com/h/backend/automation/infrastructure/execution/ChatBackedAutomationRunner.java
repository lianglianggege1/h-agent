package com.h.backend.automation.infrastructure.execution;

import com.h.backend.automation.application.AutomationExecutionAdapter.AutomationExecutionResult;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.chat.application.ChatService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.domain.approval.ApprovalMode;
import com.h.backend.chat.interfaces.dto.ChatSessionOpenDto;
import com.h.backend.chat.interfaces.dto.ChatStreamEvent;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
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

    public ChatBackedAutomationRunner(
            ChatSessionService chatSessionService,
            ChatService chatService,
            AutomationProperties properties
    ) {
        this.chatSessionService = chatSessionService;
        this.chatService = chatService;
        this.properties = properties;
    }

    public AutomationExecutionResult run(AutomationTask task, ApprovalMode approvalMode) {
        ChatSessionOpenDto opened = chatSessionService.createSession(
                task.userId(), null, task.agentId(), approvalMode, null
        );
        String sessionId = opened.session().sessionId();
        Long promptId = opened.session().promptId();
        StringBuilder chunks = new StringBuilder();
        AtomicReference<String> finalOutput = new AtomicReference<>();
        AtomicReference<String> terminalError = new AtomicReference<>();

        chatService.streamChat(
                        task.userId(), promptId, task.agentId(), sessionId,
                        task.instruction(), List.of()
                )
                .doOnNext(event -> capture(event, chunks, finalOutput, terminalError))
                .takeUntil(ChatBackedAutomationRunner::terminal)
                .blockLast(properties.getExecutionTimeout());

        if (terminalError.get() != null) {
            throw new IllegalStateException(terminalError.get());
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
        if ("error".equals(event.type()) || "blocked".equals(event.type())
                || "action_required".equals(event.type())) {
            terminalError.set(event.content() == null || event.content().isBlank()
                    ? "自动化执行需要人工处理，已终止"
                    : event.content());
        }
    }

    private static boolean terminal(ChatStreamEvent event) {
        return switch (event.type()) {
            case "done", "error", "blocked", "action_required" -> true;
            default -> false;
        };
    }
}
