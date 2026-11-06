package com.h.backend.document.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.*;

import lombok.Data;

import java.time.OffsetDateTime;

/** 文档任务持久化记录；revision 用于工作线程检查点的并发控制。 */
@Data
@TableName("chat_document_tasks")
public class DocumentTaskEntity {
    @TableId(type = IdType.INPUT)
    /** 稳定的本地任务标识。 */
    private String id;

    /** 所属用户 ID，所有查询与状态变更均按用户隔离。 */
    private Long userId;

    /** 所属聊天会话 ID。 */
    private String sessionId;

    /** 原附件资源 ID，纯新建任务可为空。 */
    private String resourceId;

    /** 同一用户会话中的稳定幂等键，用于防止重复解析与交付。 */
    private String requestKey;

    /** 文档业务状态，与原请求续跑状态分别维护。 */
    private String status;

    /** 只保存在后端的内部状态，包含尚未放行的正文，禁止直接作为 API 或工具响应。 */
    private String stateJson;

    /** 乐观版本，每次状态更新递增，过期工作线程不得覆盖用户决定。 */
    private Long revision;

    /** 调度租约截止时间，数据库行锁保证多实例领取互斥。 */
    private OffsetDateTime leaseUntil;

    /** 任务创建时间。 */
    private OffsetDateTime createdAt;

    /** 最后状态变更时间。 */
    private OffsetDateTime updatedAt;
}
