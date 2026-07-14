package com.h.backend.generation.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

@TableName("generation_tasks")
public class GenerationTaskEntity {
    @TableId(type = IdType.INPUT)
    private String id;
    @TableField("user_id")
    private Long userId;
    @TableField("session_id")
    private String sessionId;
    @TableField("generation_type")
    private String generationType;
    private String provider;
    private String status;
    @TableField("spec_json")
    private String specJson;
    @TableField("provider_task_id")
    private String providerTaskId;
    @TableField("provider_status")
    private String providerStatus;
    @TableField("provider_file_id")
    private String providerFileId;
    @TableField("chat_message_id")
    private Long chatMessageId;
    @TableField("artifact_id")
    private String artifactId;
    @TableField("artifact_storage_type")
    private String artifactStorageType;
    @TableField("artifact_storage_key")
    private String artifactStorageKey;
    @TableField("artifact_mime_type")
    private String artifactMimeType;
    @TableField("artifact_file_name")
    private String artifactFileName;
    @TableField("artifact_size")
    private Long artifactSize;
    @TableField("retry_count")
    private Integer retryCount;
    @TableField("next_poll_at")
    private LocalDateTime nextPollAt;
    @TableField("failure_message")
    private String failureMessage;
    @TableField("created_at")
    private LocalDateTime createdAt;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
    @TableField("completed_at")
    private LocalDateTime completedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Long getUserId() {
        return userId;
    }

    public void setUserId(Long userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getGenerationType() {
        return generationType;
    }

    public void setGenerationType(String generationType) {
        this.generationType = generationType;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getSpecJson() {
        return specJson;
    }

    public void setSpecJson(String specJson) {
        this.specJson = specJson;
    }

    public String getProviderTaskId() {
        return providerTaskId;
    }

    public void setProviderTaskId(String providerTaskId) {
        this.providerTaskId = providerTaskId;
    }

    public String getProviderStatus() {
        return providerStatus;
    }

    public void setProviderStatus(String providerStatus) {
        this.providerStatus = providerStatus;
    }

    public String getProviderFileId() {
        return providerFileId;
    }

    public void setProviderFileId(String providerFileId) {
        this.providerFileId = providerFileId;
    }

    public Long getChatMessageId() {
        return chatMessageId;
    }

    public void setChatMessageId(Long chatMessageId) {
        this.chatMessageId = chatMessageId;
    }

    public String getArtifactId() {
        return artifactId;
    }

    public void setArtifactId(String artifactId) {
        this.artifactId = artifactId;
    }

    public String getArtifactStorageType() {
        return artifactStorageType;
    }

    public void setArtifactStorageType(String artifactStorageType) {
        this.artifactStorageType = artifactStorageType;
    }

    public String getArtifactStorageKey() {
        return artifactStorageKey;
    }

    public void setArtifactStorageKey(String artifactStorageKey) {
        this.artifactStorageKey = artifactStorageKey;
    }

    public String getArtifactMimeType() {
        return artifactMimeType;
    }

    public void setArtifactMimeType(String artifactMimeType) {
        this.artifactMimeType = artifactMimeType;
    }

    public String getArtifactFileName() {
        return artifactFileName;
    }

    public void setArtifactFileName(String artifactFileName) {
        this.artifactFileName = artifactFileName;
    }

    public Long getArtifactSize() {
        return artifactSize;
    }

    public void setArtifactSize(Long artifactSize) {
        this.artifactSize = artifactSize;
    }

    public Integer getRetryCount() {
        return retryCount;
    }

    public void setRetryCount(Integer retryCount) {
        this.retryCount = retryCount;
    }

    public LocalDateTime getNextPollAt() {
        return nextPollAt;
    }

    public void setNextPollAt(LocalDateTime nextPollAt) {
        this.nextPollAt = nextPollAt;
    }

    public String getFailureMessage() {
        return failureMessage;
    }

    public void setFailureMessage(String failureMessage) {
        this.failureMessage = failureMessage;
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

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }
}
