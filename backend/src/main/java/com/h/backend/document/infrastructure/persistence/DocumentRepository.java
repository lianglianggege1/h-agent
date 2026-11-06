package com.h.backend.document.infrastructure.persistence;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.h.backend.common.exception.BusinessException;
import com.h.backend.document.domain.DocumentTask;
import com.h.backend.document.infrastructure.persistence.entity.*;
import com.h.backend.document.infrastructure.persistence.mapper.*;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.function.Consumer;

/** 文档持久化适配器，组合 MyBatis-Plus CRUD 与 PostgreSQL 专用 XML SQL。网络请求不得放入此处的行锁事务。 */
@Repository
public class DocumentRepository {
    private final DocumentTaskMapper tasks;
    private final ObjectMapper json;
    private final TransactionTemplate transactions;

    public DocumentRepository(
            DocumentTaskMapper tasks,
            ObjectMapper json,
            org.springframework.transaction.PlatformTransactionManager manager) {
        this.tasks = tasks;
        this.json = json;
        this.transactions = new TransactionTemplate(manager);
    }

    public DocumentTask insert(DocumentTask task) {
        var row = new DocumentTaskEntity();
        row.setId(task.getId());
        row.setUserId(task.getUserId());
        row.setSessionId(task.getSessionId());
        row.setResourceId(task.getResourceId());
        row.setRequestKey(task.getRequestKey());
        row.setStatus(task.getStatus());
        row.setStateJson(json.writeValueAsString(task));
        tasks.insertIdempotent(row);
        return decode(
                tasks.selectOne(
                        owned(task.getUserId(), task.getSessionId())
                                .eq(DocumentTaskEntity::getRequestKey, task.getRequestKey())));
    }

    public DocumentTask find(Long user, String session, String id) {
        return decode(tasks.selectOne(owned(user, session).eq(DocumentTaskEntity::getId, id)));
    }

    public List<DocumentTask> list(Long user, String session) {
        return tasks
                .selectList(
                        owned(user, session)
                                .orderByDesc(DocumentTaskEntity::getCreatedAt)
                                .last("LIMIT 100"))
                .stream()
                .map(this::decode)
                .toList();
    }

    /** 行锁覆盖确认、取消等状态转换，异常将回滚整个变更。 */
    public DocumentTask change(
            Long user, String session, String id, Consumer<DocumentTask> mutation) {
        return transactions.execute(
                status -> {
                    var task = decode(tasks.lockOwned(user, session, id));
                    mutation.accept(task);
                    task.setRevision(task.getRevision() + 1);
                    tasks.update(
                            null,
                            new LambdaUpdateWrapper<DocumentTaskEntity>()
                                    .eq(DocumentTaskEntity::getId, id)
                                    .eq(DocumentTaskEntity::getUserId, user)
                                    .set(DocumentTaskEntity::getStatus, task.getStatus())
                                    .set(
                                            DocumentTaskEntity::getStateJson,
                                            json.writeValueAsString(task))
                                    .set(DocumentTaskEntity::getRevision, task.getRevision())
                                    .set(DocumentTaskEntity::getUpdatedAt, OffsetDateTime.now()));
                    return task;
                });
    }

    public List<DocumentTask> claim() {
        return transactions.execute(status -> tasks.claim().stream().map(this::decode).toList());
    }

    /** 乐观检查点拒绝过期工作线程，保护同时发生的取消或人工确认。 */
    public boolean checkpoint(DocumentTask task) {
        int updated =
                tasks.update(
                        null,
                        new LambdaUpdateWrapper<DocumentTaskEntity>()
                                .eq(DocumentTaskEntity::getId, task.getId())
                                .eq(DocumentTaskEntity::getRevision, task.getRevision())
                                .set(DocumentTaskEntity::getStatus, task.getStatus())
                                .set(
                                        DocumentTaskEntity::getStateJson,
                                        json.writeValueAsString(task))
                                .setSql("revision=revision+1")
                                .set(DocumentTaskEntity::getUpdatedAt, OffsetDateTime.now()));
        if (updated == 1) task.setRevision(task.getRevision() + 1);
        return updated == 1;
    }

    public Long activeAnchor(Long user, String session) {
        return tasks.activeAnchor(user, session);
    }

    public String runStatus(Long runId) {
        String status = tasks.runStatus(runId);
        return status == null ? "MISSING" : status;
    }

    public void release(String id) {
        tasks.update(
                null,
                new LambdaUpdateWrapper<DocumentTaskEntity>()
                        .eq(DocumentTaskEntity::getId, id)
                        .set(DocumentTaskEntity::getLeaseUntil, null));
    }

    public int requeueUnavailable() {
        return tasks.requeueUnavailable();
    }

    private LambdaQueryWrapper<DocumentTaskEntity> owned(Long user, String session) {
        return new LambdaQueryWrapper<DocumentTaskEntity>()
                .eq(DocumentTaskEntity::getUserId, user)
                .eq(DocumentTaskEntity::getSessionId, session);
    }

    private DocumentTask decode(DocumentTaskEntity row) {
        if (row == null) throw new BusinessException(40404, "文档任务不存在");
        var task = json.readValue(row.getStateJson(), DocumentTask.class);
        task.setRevision(row.getRevision());
        return task;
    }
}
