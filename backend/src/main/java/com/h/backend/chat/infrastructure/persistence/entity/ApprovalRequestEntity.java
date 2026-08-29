package com.h.backend.chat.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@TableName("approval_requests")
public class ApprovalRequestEntity {
    @TableId
    private String approvalId;
    private Long runId;
    private Long userId;
    private String rootSessionId;
    private String sessionId;
    private String requestKey;
    private String replyId;
    private String subagentExecutionId;
    private String approvalMode;
    private String toolCallIdsJson;
    private String toolNamesJson;
    private String displayItemsJson;
    private String status;
    private String decision;
    private Integer version;
    private LocalDateTime requestedAt;
    private LocalDateTime decidedAt;
    private Long decidedBy;
    private LocalDateTime updatedAt;
}
