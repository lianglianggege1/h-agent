package com.h.otheragents.team.capability;

import com.h.capabilities.seam.matrix.TeamActorContext;
import com.h.capabilities.seam.matrix.TeamDocumentContinuation;
import com.h.capabilities.document.application.DocumentWorkflow;
import com.h.capabilities.document.domain.DocumentTask;
import com.h.capabilities.document.infrastructure.config.DocumentProperties;
import com.h.capabilities.document.infrastructure.persistence.DocumentRepository;
import com.h.matrixteam.TeamMessage;
import com.h.matrixteam.TeamRoomBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker 侧文档任务调度器：只领取本服务提交的任务（owner 隔离共享任务表），
 * 处理完成后沿创建者身份（actor）把续跑路由回原 Worker，结果发回原房间。
 * 本服务无 @EnableScheduling，用独立调度线程驱动。
 */
public class TeamDocumentJob implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TeamDocumentJob.class);
    private static final List<String> RUNNABLE =
            List.of("PARSING", "QUEUED", "OCR_RUNNING", "VERIFYING", "GENERATING");

    private final DocumentRepository repository;
    private final DocumentWorkflow workflow;
    private final DocumentProperties properties;
    private final TeamDocumentContinuation continuation;
    private final TeamRoomBridge.Handler workerHandler;
    private final TeamRoomBridge.Handler springWorkerHandler;
    private final String springWorkerUserId;
    private final String humanMatrixUserId;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> Thread.ofPlatform().name("team-document-scheduler").daemon(true).unstarted(runnable));
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(
                    runnable -> Thread.ofPlatform().name("team-document-worker").daemon(true).unstarted(runnable));
    private final ExecutorService continuationWorker =
            Executors.newCachedThreadPool(
                    runnable -> Thread.ofPlatform().name("team-document-continuation-").daemon(true).unstarted(runnable));
    private final AtomicBoolean running = new AtomicBoolean();

    public TeamDocumentJob(
            DocumentRepository repository,
            DocumentWorkflow workflow,
            DocumentProperties properties,
            TeamDocumentContinuation continuation,
            TeamRoomBridge.Handler workerHandler,
            TeamRoomBridge.Handler springWorkerHandler,
            String springWorkerUserId,
            String humanMatrixUserId) {
        this.repository = repository;
        this.workflow = workflow;
        this.properties = properties;
        this.continuation = continuation;
        this.workerHandler = workerHandler;
        this.springWorkerHandler = springWorkerHandler;
        this.springWorkerUserId = springWorkerUserId;
        this.humanMatrixUserId = humanMatrixUserId;
    }

    public void start() {
        scheduler.scheduleWithFixedDelay(this::tick, 2, 2, TimeUnit.SECONDS);
        log.info("Team document job started owner={}", properties.getOwner());
    }

    private void tick() {
        if (!running.compareAndSet(false, true)) return;
        worker.submit(() -> {
            try {
                workflow.recoverAvailableOcr();
                for (var task : repository.claim(properties.getOwner())) {
                    boolean releaseHere = true;
                    try (var trace = MDC.putCloseable("traceId",
                                 task.getTraceId() == null ? task.getId().replace("-", "") : task.getTraceId());
                         var taskContext = MDC.putCloseable("documentTaskId", task.getId())) {
                        if (RUNNABLE.contains(task.getStatus())) {
                            // 后台处理线程没有 agent 上下文，交付归属按任务创建者恢复。
                            TeamActorContext.set(task.getActor());
                            try {
                                workflow.process(task);
                            } finally {
                                TeamActorContext.clear();
                            }
                        } else {
                            continuationWorker.submit(() -> resumeClaimed(task));
                            releaseHere = false;
                        }
                    } catch (RuntimeException failure) {
                        log.error("Team document task step failed traceId={} taskId={} errorType={}",
                                task.getTraceId(), task.getId(), failure.getClass().getSimpleName(), failure);
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
        try (var trace = MDC.putCloseable("traceId",
                     task.getTraceId() == null ? task.getId().replace("-", "") : task.getTraceId());
             var taskContext = MDC.putCloseable("documentTaskId", task.getId())) {
            var handler = springWorkerUserId.equals(task.getActor()) ? springWorkerHandler : workerHandler;
            continuation.resume(task, prompt -> handler.handle(new TeamMessage(
                    "doc-continuation:" + task.getId(), task.getSessionId(), humanMatrixUserId,
                    prompt, List.of(), false, null, null)));
        } catch (Exception failure) {
            log.error("Team document continuation worker failed traceId={} taskId={}",
                    task.getTraceId(), task.getId(), failure);
        } finally {
            repository.release(task.getId());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        worker.shutdownNow();
        continuationWorker.shutdownNow();
    }
}