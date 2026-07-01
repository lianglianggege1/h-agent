package com.h.backend.chat.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("chat_memory_snapshots")
public class ChatMemorySnapshotEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("session_record_id")
    private Long sessionRecordId;

    @TableField("session_id")
    private String sessionId;

    @TableField("user_id")
    private Long userId;

    @TableField("prompt_id")
    private Long promptId;

    @TableField("agent_id")
    private String agentId;

    @TableField("memory_scope")
    private String memoryScope;

    @TableField("memory_payload_json")
    private String memoryPayloadJson;

    @TableField("memory_format")
    private String memoryFormat;

    @TableField("window_size")
    private Integer windowSize;

    @TableField("source_message_count")
    private Integer sourceMessageCount;

    @TableField("snapshot_version")
    private Long snapshotVersion;

    @TableField("last_compacted_at")
    private LocalDateTime lastCompactedAt;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getSessionRecordId() {
        return sessionRecordId;
    }

    public void setSessionRecordId(Long sessionRecordId) {
        this.sessionRecordId = sessionRecordId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public Long getPromptId() {
        return promptId;
    }

    public void setPromptId(Long promptId) {
        this.promptId = promptId;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public String getMemoryScope() {
        return memoryScope;
    }

    public void setMemoryScope(String memoryScope) {
        this.memoryScope = memoryScope;
    }

    public String getMemoryPayloadJson() {
        return memoryPayloadJson;
    }

    public void setMemoryPayloadJson(String memoryPayloadJson) {
        this.memoryPayloadJson = memoryPayloadJson;
    }

    public String getMemoryFormat() {
        return memoryFormat;
    }

    public void setMemoryFormat(String memoryFormat) {
        this.memoryFormat = memoryFormat;
    }

    public Integer getWindowSize() {
        return windowSize;
    }

    public void setWindowSize(Integer windowSize) {
        this.windowSize = windowSize;
    }

    public Integer getSourceMessageCount() {
        return sourceMessageCount;
    }

    public void setSourceMessageCount(Integer sourceMessageCount) {
        this.sourceMessageCount = sourceMessageCount;
    }

    public Long getSnapshotVersion() {
        return snapshotVersion;
    }

    public void setSnapshotVersion(Long snapshotVersion) {
        this.snapshotVersion = snapshotVersion;
    }

    public LocalDateTime getLastCompactedAt() {
        return lastCompactedAt;
    }

    public void setLastCompactedAt(LocalDateTime lastCompactedAt) {
        this.lastCompactedAt = lastCompactedAt;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
