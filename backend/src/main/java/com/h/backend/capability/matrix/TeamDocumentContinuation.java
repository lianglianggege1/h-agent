package com.h.backend.capability.matrix;

import com.h.backend.common.exception.BusinessException;
import com.h.backend.document.application.DocumentWorkflow;
import com.h.backend.document.domain.DocumentTask;
import com.h.backend.document.infrastructure.persistence.DocumentRepository;
import com.h.matrixteam.TeamRunTracker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 团队房间的文档任务续跑：不走聊天会话/AgentRun 表，沿房间锚点重新调用原 Agent，
 * 结果由 handler 直接发回房间。原 run 仍在执行时跳过；原 run 在任务定型后已结束则视为已应答。
 */
public class TeamDocumentContinuation {

    @FunctionalInterface
    public interface Invoker {
        void invoke(String prompt) throws Exception;
    }

    private static final Logger log = LoggerFactory.getLogger(TeamDocumentContinuation.class);

    private final DocumentRepository repository;
    private final DocumentWorkflow workflow;
    private final TeamRunTracker tracker;

    public TeamDocumentContinuation(
            DocumentRepository repository, DocumentWorkflow workflow, TeamRunTracker tracker) {
        this.repository = repository;
        this.workflow = workflow;
        this.tracker = tracker;
    }

    public void resume(DocumentTask task, Invoker invoker) {
        if (task.isContinuationCancelled()) return;
        try {
            workflow.require(task.getUserId(), task.getSessionId(), task.getId());
        } catch (BusinessException missing) {
            log.error("Team document resume source unavailable traceId={} taskId={}",
                    task.getTraceId(), task.getId(), missing);
            repository.change(task.getUserId(), task.getSessionId(), task.getId(), current -> {
                current.setResumeStatus("CANCELLED");
                current.setContinuationCancelled(true);
                current.setDetail("原附件或请求已不可用，原任务已取消");
            });
            return;
        }
        String room = task.getSessionId();
        if (tracker.isActive(room)) return;
        long lastEndedAt = tracker.lastEndedAt(room);
        if (lastEndedAt > 0
                && (task.getRecognizedAtMillis() == 0 || lastEndedAt >= task.getRecognizedAtMillis())) {
            log.info("Team document continuation already answered traceId={} taskId={} room={}",
                    task.getTraceId(), task.getId(), room);
            repository.change(task.getUserId(), task.getSessionId(), task.getId(), current -> {
                if ("QUEUED".equals(current.getResumeStatus())) current.setResumeStatus("DONE");
            });
            return;
        }
        if ("RUNNING".equals(task.getResumeStatus())) {
            // 上一次续跑在定型前被中断，回退排队等待重试。
            repository.change(task.getUserId(), task.getSessionId(), task.getId(), current -> {
                if ("RUNNING".equals(current.getResumeStatus())) current.setResumeStatus("QUEUED");
            });
            return;
        }
        if (!"QUEUED".equals(task.getResumeStatus())) return;
        String prompt =
                "继续完成原用户请求：\n"
                        + task.getOriginalRequest()
                        + "\n\n文档任务已有新状态（复用此任务，不要重复解析或交付）：\n"
                        + workflow.toolView(task, 0)
                        + "\n如正文还有 nextOffset，调用 parse_document(taskId,offset) 继续读取。低质量状态按诊断处理。";
        repository.change(task.getUserId(), task.getSessionId(), task.getId(), current -> {
            if (current.getRevision() != task.getRevision()
                    || !"QUEUED".equals(current.getResumeStatus()))
                throw new IllegalStateException("Continuation state changed");
            current.setResumeStatus("RUNNING");
        });
        try {
            log.info("Team document continuation started traceId={} taskId={} room={}",
                    task.getTraceId(), task.getId(), room);
            invoker.invoke(prompt);
            repository.change(task.getUserId(), task.getSessionId(), task.getId(), current -> {
                if ("RUNNING".equals(current.getResumeStatus())) current.setResumeStatus("DONE");
            });
            log.info("Team document continuation settled traceId={} taskId={}", task.getTraceId(), task.getId());
        } catch (Exception failure) {
            log.error("Team document continuation failed traceId={} taskId={}",
                    task.getTraceId(), task.getId(), failure);
            repository.change(task.getUserId(), task.getSessionId(), task.getId(), current -> {
                if ("RUNNING".equals(current.getResumeStatus())) current.setResumeStatus("QUEUED");
            });
        }
    }
}