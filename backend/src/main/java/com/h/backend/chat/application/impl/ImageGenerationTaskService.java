package com.h.backend.chat.application.impl;

import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.chat.application.ImageGenerationService;
import com.h.backend.chat.application.ImageGenerationService.ImageGenerationCommand;
import com.h.backend.chat.domain.model.ChatMessagePayload;
import com.h.backend.chat.infrastructure.persistence.mapper.ImageGenerationTaskMapper;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.shared.infrastructure.web.ExecutionLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

@Service
public class ImageGenerationTaskService {
    private static final Logger log = LoggerFactory.getLogger(ImageGenerationTaskService.class);
    private static final String UNKNOWN_MESSAGE =
            "图片生成执行中断或超时，无法确认供应商结果。为避免重复计费，系统未自动重试，请按需重新发起生成。";
    private final ImageGenerationTaskMapper tasks;
    private final ChatSessionService sessions;
    private final ImageGenerationService generator;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;

    public ImageGenerationTaskService(ImageGenerationTaskMapper tasks, ChatSessionService sessions,
                                      ImageGenerationService generator, TransactionTemplate transaction, ObjectMapper json) {
        this.tasks = tasks;
        this.sessions = sessions;
        this.generator = generator;
        this.transaction = transaction;
        this.json = json;
    }

    public ChatSessionMessageDto submit(ImageGenerationCommand command) {
        if (command.prompt() == null || command.prompt().isBlank()) {
            throw new IllegalArgumentException("图片提示词不能为空");
        }
        return transaction.execute(ignored -> {
            var pending = sessions.appendResourceMessage(command.userId(), command.sessionId(),
                    "图片生成中，请继续聊天。", "IMAGE", List.of());
            Long messageId = Long.valueOf(pending.id());
            var message = sessions.updateImageMessage(command.userId(), command.sessionId(), messageId,
                    "图片生成中，请继续聊天。", payload(command, "PENDING"), List.of());
            tasks.enqueue(messageId, json.writeValueAsString(new QueuedCommand(command, ExecutionLogContext.captureCorrelation())));
            return message;
        });
    }

    public boolean processNext() {
        var task = tasks.claim();
        if (task == null) return false;
        var queued = readCommand(task);
        try (var scope = ExecutionLogContext.restoreCorrelation(queued.correlation())) {
            process(task, queued.command());
        }
        return true;
    }

    private void process(ImageGenerationTaskMapper.Task task, ImageGenerationCommand command) {
        try {
            var message = sessions.updateImageMessage(command.userId(), command.sessionId(), task.messageId(),
                    "图片生成中，请继续聊天。", payload(command, "IN_PROGRESS"), List.of());
            if (!"IN_PROGRESS".equals(message.payload().status())) {
                tasks.finish(task.messageId(), queueStatus(message));
                return;
            }
            generator.generateImage(command, task.messageId());
            tasks.finish(task.messageId(), "SUCCEEDED");
        } catch (RuntimeException exception) {
            log.warn("Image generation task failed messageId={}", task.messageId(), exception);
            if (uncertainOutcome(exception)) {
                finishWithoutRetry(task, command, "SUBMISSION_UNKNOWN", UNKNOWN_MESSAGE);
            } else {
                finishWithoutRetry(task, command, "FAILED", "图片生成失败，请稍后重新发起生成。");
            }
        }
    }

    public void recoverStale(long seconds) {
        for (var task : tasks.findStale(seconds)) {
            try {
                var queued = readCommand(task);
                try (var scope = ExecutionLogContext.restoreCorrelation(queued.correlation())) {
                    finishWithoutRetry(task, queued.command(), "SUBMISSION_UNKNOWN", UNKNOWN_MESSAGE);
                }
            } catch (RuntimeException exception) {
                log.warn("Could not recover image task messageId={}", task.messageId(), exception);
            }
        }
    }

    private record QueuedCommand(ImageGenerationCommand command, Map<String, String> correlation) {}

    private QueuedCommand readCommand(ImageGenerationTaskMapper.Task task) {
        // Existing rows contain the command directly; they remain runnable after upgrading.
        if (json.readTree(task.commandJson()).has("command")) {
            return json.readValue(task.commandJson(), QueuedCommand.class);
        }
        return new QueuedCommand(json.readValue(task.commandJson(), ImageGenerationCommand.class), null);
    }

    private void finishWithoutRetry(ImageGenerationTaskMapper.Task task, ImageGenerationCommand command,
                                    String status, String content) {
        // The message lock serializes recovery with result attachment. A committed result wins.
        var message = sessions.updateImageMessage(command.userId(), command.sessionId(), task.messageId(),
                content, payload(command, status), List.of());
        tasks.finish(task.messageId(), queueStatus(message));
    }

    private String queueStatus(ChatSessionMessageDto message) {
        String actual = message.payload().status();
        return "READY".equals(actual) ? "SUCCEEDED" : actual;
    }

    private boolean uncertainOutcome(Throwable exception) {
        if (Thread.currentThread().isInterrupted()) return true;
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.springframework.web.client.ResourceAccessException
                    || cause instanceof java.io.InterruptedIOException
                    || cause instanceof java.net.http.HttpTimeoutException
                    || cause instanceof InterruptedException) return true;
        }
        return false;
    }

    private ChatMessagePayload payload(ImageGenerationCommand command, String status) {
        var payload = new ChatMessagePayload();
        payload.setPrompt(command.prompt().trim());
        payload.setProvider("MINIMAX");
        payload.setStatus(status);
        payload.setTriggerSource(command.triggerSource());
        payload.setSourceResourceId(command.sourceResourceId());
        payload.setParentImageMessageId(command.parentImageMessageId());
        payload.setOperationType(command.operationType());
        return payload;
    }
}
