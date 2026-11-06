package com.h.backend.document.infrastructure.scheduling;

import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.ChatService;
import com.h.backend.chat.application.ChatSessionService;
import com.h.backend.document.application.DocumentWorkflow;
import com.h.backend.document.domain.DocumentContinuation;
import com.h.backend.document.domain.DocumentTask;
import com.h.backend.document.infrastructure.persistence.DocumentRepository;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/** 持久化任务调度器：领取跨实例租约，在恢复执行时重建任务日志 MDC，并在 finally 中清理。 */
@Component
public class DocumentJob {
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DocumentJob.class);
    private static final java.util.List<String> RUNNABLE =
            java.util.List.of("PARSING", "QUEUED", "OCR_RUNNING", "VERIFYING", "GENERATING");
    private final DocumentRepository repository;
    private final DocumentWorkflow workflow;
    private final ObjectProvider<ChatService> chats;
    private final AgentRunService runs;
    private final ChatSessionService sessions;
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    Thread.ofPlatform().name("document-worker").factory());
    /**
     * Agent continuation streams can stay open while the model responds. Keep them off the
     * document worker so a slow chat run cannot stop OCR/parse jobs from being claimed.
     */
    private final ExecutorService continuationWorker =
            Executors.newCachedThreadPool(
                    Thread.ofPlatform().name("document-continuation-", 0).factory());
    private final AtomicBoolean running = new AtomicBoolean();

    public DocumentJob(
            DocumentRepository repository,
            DocumentWorkflow workflow,
            ObjectProvider<ChatService> chats,
            AgentRunService runs,
            ChatSessionService sessions) {
        this.repository = repository;
        this.workflow = workflow;
        this.chats = chats;
        this.runs = runs;
        this.sessions = sessions;
    }

    @Scheduled(fixedDelayString = "${chat.documents.poll-delay:2s}")
    public void tick() {
        if (!running.compareAndSet(false, true)) return;
        worker.submit(
                () -> {
                    try {
                        workflow.recoverAvailableOcr();
                        for (var task : repository.claim()) {
                            boolean releaseHere = true;
                            try (var trace =
                                            org.slf4j.MDC.putCloseable(
                                                    "traceId",
                                                    task.getTraceId() == null
                                                            ? task.getId().replace("-", "")
                                                            : task.getTraceId());
                                    var taskContext =
                                            org.slf4j.MDC.putCloseable(
                                                    "documentTaskId", task.getId())) {
                                if (RUNNABLE.contains(task.getStatus())) workflow.process(task);
                                else {
                                    continuationWorker.submit(() -> resumeClaimed(task));
                                    // The continuation owns this task's lease until its stream settles.
                                    releaseHere = false;
                                }
                            } catch (RuntimeException failure) {
                                log.error(
                                        "Document task step failed traceId={} taskId={}"
                                                + " errorType={}",
                                        task.getTraceId(),
                                        task.getId(),
                                        failure.getClass().getSimpleName(),
                                        failure);
                            } finally {
                                if (releaseHere) repository.release(task.getId());
                            }
                        }
                    } finally {
                        running.set(false);
                    }
                });
    }

    private void resumeClaimed(DocumentTask task) {
        try (var trace =
                        org.slf4j.MDC.putCloseable(
                                "traceId",
                                task.getTraceId() == null
                                        ? task.getId().replace("-", "")
                                        : task.getTraceId());
                var taskContext = org.slf4j.MDC.putCloseable("documentTaskId", task.getId())) {
            resume(task);
        } catch (RuntimeException failure) {
            log.error(
                    "Document continuation worker failed traceId={} taskId={}",
                    task.getTraceId(),
                    task.getId(),
                    failure);
        } finally {
            repository.release(task.getId());
        }
    }

    void resume(DocumentTask task) {
        if (task.isContinuationCancelled()) return;
        try {
            workflow.require(task.getUserId(), task.getSessionId(), task.getId());
        } catch (com.h.backend.common.exception.BusinessException missing) {
            log.error("Document resume source unavailable traceId={} taskId={}",
                    task.getTraceId(), task.getId(), missing);
            repository.change(
                    task.getUserId(),
                    task.getSessionId(),
                    task.getId(),
                    current -> {
                        current.setResumeStatus("CANCELLED");
                        current.setContinuationCancelled(true);
                        current.setDetail("原附件或请求已不可用，原任务已取消");
                    });
            return;
        }
        if ("RUNNING".equals(task.getResumeStatus()) && task.getResumedRunId() != null) {
            String status = repository.runStatus(task.getResumedRunId());
            if ("WAITING_APPROVAL".equals(status)) return;
            // A live continuation owns the 12-minute lease and has a 10-minute deadline.
            // Reclaiming RUNNING here means that its worker was interrupted before settlement.
            if ("RUNNING".equals(status)) runs.failRun(task.getResumedRunId(), "文档续跑中断，请从文档卡继续");
            repository.change(
                    task.getUserId(),
                    task.getSessionId(),
                    task.getId(),
                    current -> {
                        if (task.getResumedRunId().equals(current.getResumedRunId())
                                && "RUNNING".equals(current.getResumeStatus()))
                            current.setResumeStatus(
                                    java.util.List.of("COMPLETED", "SUCCESS", "SUCCEEDED")
                                                    .contains(status)
                                            ? "DONE"
                                            : "NEEDS_RESUME");
                    });
            return;
        }
        if ("RUNNING".equals(task.getResumeStatus()) && task.getResumedRunId() == null) {
            repository.change(
                    task.getUserId(),
                    task.getSessionId(),
                    task.getId(),
                    current -> {
                        if (current.getResumedRunId() == null
                                && "RUNNING".equals(current.getResumeStatus()))
                            current.setResumeStatus("QUEUED");
                    });
            return;
        }
        if ("QUEUED".equals(task.getResumeStatus())
                && task.getResumedRunId() == null
                && task.getAnchorMessageId() != null) {
            var originalRun =
                    runs.findByUserMessage(
                            task.getUserId(), task.getSessionId(), task.getAnchorMessageId());
            if (originalRun != null
                    && "SUCCEEDED".equals(originalRun.status())
                    && originalRun.assistantMessageId() != null
                    && originalRun.completedAt() != null) {
                long originalCompletedAt =
                        originalRun
                                .completedAt()
                                .atZone(java.time.ZoneId.systemDefault())
                                .toInstant()
                                .toEpochMilli();
                // A successful run that ended before recognition only delivered the
                // provisional "processing" acknowledgment; it still needs a continuation.
                if (task.getRecognizedAtMillis() == 0
                        || originalCompletedAt >= task.getRecognizedAtMillis()) {
                    log.info(
                            "Document continuation already answered traceId={} taskId={} runId={}",
                            task.getTraceId(),
                            task.getId(),
                            originalRun.id());
                    repository.change(
                            task.getUserId(),
                            task.getSessionId(),
                            task.getId(),
                            current -> {
                                if ("QUEUED".equals(current.getResumeStatus())
                                        && current.getResumedRunId() == null)
                                    current.setResumeStatus("DONE");
                            });
                    return;
                }
                log.info(
                        "Original chat run ended before document recognition; resuming traceId={}"
                                + " taskId={} runId={}",
                        task.getTraceId(),
                        task.getId(),
                        originalRun.id());
            }
        }
        if (runs.hasOpenRun(task.getSessionId())) return;
        var meta = sessions.getSessionDetail(task.getUserId(), task.getSessionId());
        if (meta.archived()) return;
        String prompt =
                "继续完成原用户请求：\n"
                        + task.getOriginalRequest()
                        + "\n\n文档任务已有新状态（复用此任务，不要重复解析或交付）：\n"
                        + workflow.toolView(task, 0)
                        + "\n如正文还有 nextOffset，调用 parse_document(taskId,offset) 继续读取。低质量状态按诊断处理。";
        repository.change(
                task.getUserId(),
                task.getSessionId(),
                task.getId(),
                current -> {
                    if (current.getRevision() != task.getRevision()
                            || !"QUEUED".equals(current.getResumeStatus()))
                        throw new IllegalStateException("Continuation state changed");
                    current.setResumeStatus("RUNNING");
                });
        var cancellation = new com.h.backend.chat.application.agui.ChatExecutionCancellation();
        var cancelled =
                reactor.core.publisher.Flux.interval(Duration.ofSeconds(1))
                        .filter(
                                tick ->
                                        repository
                                                .find(
                                                        task.getUserId(),
                                                        task.getSessionId(),
                                                        task.getId())
                                                .isContinuationCancelled())
                        .doOnNext(tick -> cancellation.cancel())
                        .next();
        try {
            var events =
                    chats.getObject()
                            .streamChat(
                                    task.getUserId(),
                                    meta.promptId(),
                                    meta.agentId(),
                                    task.getSessionId(),
                                    prompt,
                                    java.util.List.of())
                            .contextWrite(
                                    context ->
                                            context.put(
                                                            DocumentContinuation.class,
                                                            new DocumentContinuation(
                                                                    task.getId(),
                                                                    task.getAnchorMessageId()))
                                                    .put(
                                                            com.h.backend.chat.application.agui
                                                                    .ChatExecutionCancellation
                                                                    .CONTEXT_KEY,
                                                            cancellation))
                            .takeUntilOther(cancelled)
                            .collectList()
                            .block(Duration.ofMinutes(10));
            repository.change(
                    task.getUserId(),
                    task.getSessionId(),
                    task.getId(),
                    current -> {
                        if (!"RUNNING".equals(current.getResumeStatus())) return;
                        boolean error =
                                events == null
                                        || events.stream()
                                                .anyMatch(
                                                        e ->
                                                                "error".equals(e.type())
                                                                        || "blocked"
                                                                                .equals(e.type()));
                        current.setResumeStatus(
                                error
                                        ? current.getResumedRunId() == null
                                                ? "QUEUED"
                                                : "NEEDS_RESUME"
                                        : "DONE");
                    });
        } catch (RuntimeException failure) {
            log.error("Document resume failed traceId={} taskId={} runId={}",
                    task.getTraceId(), task.getId(), task.getResumedRunId(), failure);
            cancellation.cancel();
            repository.change(
                    task.getUserId(),
                    task.getSessionId(),
                    task.getId(),
                    current -> {
                        if ("RUNNING".equals(current.getResumeStatus()))
                            current.setResumeStatus("NEEDS_RESUME");
                    });
        }
    }

    @PreDestroy
    public void close() {
        worker.shutdownNow();
        continuationWorker.shutdownNow();
    }
}
