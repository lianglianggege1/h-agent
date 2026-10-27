package com.h.backend.generation.domain.model;

import java.time.Instant;
import java.util.Objects;

public final class GenerationTask {
    private final String id;
    private final Long userId;
    private final String sessionId;
    private final GenerationSpec spec;
    private final Instant createdAt;

    private long version;
    private String resultText;
    private GenerationStatus status;
    private String providerTaskId;
    private String providerStatus;
    private String providerFileId;
    private Long chatMessageId;
    private GeneratedArtifact artifact;
    private int retryCount;
    private Instant nextPollAt;
    private String failureMessage;
    private Instant updatedAt;
    private Instant completedAt;

    private GenerationTask(String id, Long userId, String sessionId, GenerationSpec spec, Instant now) {
        this.id = requireText(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId must not be null");
        this.sessionId = requireText(sessionId, "sessionId");
        this.spec = Objects.requireNonNull(spec, "spec must not be null");
        this.status = GenerationStatus.PENDING_SUBMISSION;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static GenerationTask create(String id, Long userId, String sessionId, GenerationSpec spec, Instant now) {
        return new GenerationTask(id, userId, sessionId, spec, now);
    }

    public static GenerationTask rehydrate(
            String id, Long userId, String sessionId, GenerationSpec spec, GenerationStatus status,
            String providerTaskId, String providerStatus, String providerFileId, Long chatMessageId,
            GeneratedArtifact artifact, int retryCount, Instant nextPollAt, String failureMessage,
            Instant createdAt, Instant updatedAt, Instant completedAt
    ) {
        GenerationTask task = new GenerationTask(id, userId, sessionId, spec, createdAt);
        task.status = status;
        task.providerTaskId = providerTaskId;
        task.providerStatus = providerStatus;
        task.providerFileId = providerFileId;
        task.chatMessageId = chatMessageId;
        task.artifact = artifact;
        task.retryCount = retryCount;
        task.nextPollAt = nextPollAt;
        task.failureMessage = failureMessage;
        task.updatedAt = updatedAt;
        task.completedAt = completedAt;
        return task;
    }

    public void markSubmitted(String providerTaskId, Instant nextPollAt, Instant now) {
        requireStatus(GenerationStatus.PENDING_SUBMISSION);
        this.providerTaskId = requireText(providerTaskId, "providerTaskId");
        this.status = GenerationStatus.IN_PROGRESS;
        updateNextPollAt(nextPollAt, now);
    }

    public void bindChatMessage(Long chatMessageId, Instant now) {
        if (chatMessageId == null || this.chatMessageId != null) {
            throw new IllegalStateException("Chat message must be bound exactly once");
        }
        this.chatMessageId = chatMessageId;
        this.updatedAt = now;
    }

    public void recordProviderProgress(String providerStatus, Instant nextPollAt, Instant now) {
        requireStatus(GenerationStatus.IN_PROGRESS, GenerationStatus.RETRY_WAIT);
        this.providerStatus = requireText(providerStatus, "providerStatus");
        this.status = GenerationStatus.IN_PROGRESS;
        updateNextPollAt(nextPollAt, now);
    }

    public void startMaterialization(String providerFileId, Instant now) {
        requireStatus(GenerationStatus.IN_PROGRESS, GenerationStatus.RETRY_WAIT);
        this.providerFileId = requireText(providerFileId, "providerFileId");
        this.status = GenerationStatus.MATERIALIZING;
        this.nextPollAt = now.plus(java.time.Duration.ofMinutes(10));
        this.updatedAt = now;
    }

    public void complete(GeneratedArtifact artifact, Instant now) {
        requireStatus(GenerationStatus.MATERIALIZING);
        this.artifact = Objects.requireNonNull(artifact, "artifact must not be null");
        this.status = GenerationStatus.SUCCEEDED;
        this.nextPollAt = null;
        this.updatedAt = now;
        this.completedAt = now;
    }

    public void retry(String message, Instant nextPollAt, Instant now) {
        ensureNotTerminal();
        this.retryCount++;
        this.failureMessage = message;
        this.status = GenerationStatus.RETRY_WAIT;
        updateNextPollAt(nextPollAt, now);
    }

    public void fail(String message, Instant now) {
        ensureNotTerminal();
        this.failureMessage = message;
        this.status = GenerationStatus.FAILED;
        this.nextPollAt = null;
        this.updatedAt = now;
        this.completedAt = now;
    }

    public void recordEnhancedPrompt(String prompt, Instant now) {
        ensureNotTerminal(); resultText = requireText(prompt,"enhancedPrompt"); status = GenerationStatus.IN_PROGRESS;
        nextPollAt = now.plus(java.time.Duration.ofMinutes(10)); updatedAt = now;
    }
    public void completeText(String text, Instant now) {
        ensureNotTerminal();
        resultText = requireText(text, "resultText");
        status = GenerationStatus.SUCCEEDED; nextPollAt = null; updatedAt = now; completedAt = now;
    }
    public void cancel(Instant now) {
        ensureNotTerminal();
        status = GenerationStatus.CANCELLED; nextPollAt = null; updatedAt = now; completedAt = now;
    }
    public void providerRecordDeleted(Instant now) { providerStatus = "DELETED"; updatedAt = now; }
    public void submissionUnknown(String message, Instant now) {
        requireStatus(GenerationStatus.PENDING_SUBMISSION);
        status = GenerationStatus.SUBMISSION_UNKNOWN; failureMessage = message; nextPollAt = null; updatedAt = now; completedAt = now;
    }
    public long version() { return version; }
    public void restoredDetails(String result, long version) { this.resultText = result; this.version = version; }
    public void persisted(long version) { this.version = version; }
    public String resultText() { return resultText; }

    public void queueMusic(Instant now) {
        requireMusic();
        markSubmitted("music:" + id, now, now);
        providerStatus = "QUEUED";
    }

    /** Persist this claim before any paid request; an expired claim must never be replayed. */
    public void startMusic(Instant deadline, Instant now) {
        requireMusic();
        requireStatus(GenerationStatus.IN_PROGRESS);
        if (!"QUEUED".equals(providerStatus)) throw new IllegalStateException("歌曲任务已被领取");
        providerStatus = "GENERATING";
        updateNextPollAt(deadline, now);
    }

    public void recordMusicLyrics(String lyrics, Instant now) {
        requireMusic();
        requireStatus(GenerationStatus.IN_PROGRESS);
        MusicGenerationSpec.requireLyrics(lyrics);
        resultText = lyrics;
        updatedAt = now;
    }

    public void musicResultUnknown(String message, Instant now) {
        requireMusic();
        ensureNotTerminal();
        status = GenerationStatus.SUBMISSION_UNKNOWN;
        failureMessage = message;
        nextPollAt = null;
        updatedAt = now;
        completedAt = now;
    }

    private void requireMusic() {
        if (!(spec instanceof MusicGenerationSpec)) throw new IllegalStateException("不是歌曲任务");
    }

    private void updateNextPollAt(Instant nextPollAt, Instant now) {
        this.nextPollAt = Objects.requireNonNull(nextPollAt, "nextPollAt must not be null");
        this.updatedAt = now;
    }

    private void requireStatus(GenerationStatus... expected) {
        for (GenerationStatus candidate : expected) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("Task status " + status + " does not allow this operation");
    }

    private void ensureNotTerminal() {
        if (status.isTerminal()) {
            throw new IllegalStateException("Terminal task cannot change");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }

    public String id() { return id; }
    public Long userId() { return userId; }
    public String sessionId() { return sessionId; }
    public GenerationType generationType() { return spec.generationType(); }
    public String provider() { return "MINIMAX"; }
    public GenerationSpec spec() { return spec; }
    public GenerationStatus status() { return status; }
    public String providerTaskId() { return providerTaskId; }
    public String providerStatus() { return providerStatus; }
    public String providerFileId() { return providerFileId; }
    public Long chatMessageId() { return chatMessageId; }
    public GeneratedArtifact artifact() { return artifact; }
    public int retryCount() { return retryCount; }
    public Instant nextPollAt() { return nextPollAt; }
    public String failureMessage() { return failureMessage; }
    public Instant createdAt() { return createdAt; }
    public Instant updatedAt() { return updatedAt; }
    public Instant completedAt() { return completedAt; }
}
