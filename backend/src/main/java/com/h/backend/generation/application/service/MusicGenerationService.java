package com.h.backend.generation.application.service;

import com.h.backend.generation.application.port.out.GeneratedArtifactStoragePort;
import com.h.backend.generation.application.port.out.GenerationChatProjectionPort;
import com.h.backend.generation.application.port.out.GenerationTaskRepository;
import com.h.backend.generation.application.port.out.MusicProviderException;
import com.h.backend.generation.application.port.out.MusicProviderPort;
import com.h.backend.generation.application.result.SubmitGenerationResult;
import com.h.backend.generation.domain.model.GenerationStatus;
import com.h.backend.generation.domain.model.GenerationTask;
import com.h.backend.generation.domain.model.MusicGenerationSpec;
import com.h.backend.generation.infrastructure.config.MusicGenerationProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.Semaphore;

@Service
public class MusicGenerationService {
    private static final Logger log = LoggerFactory.getLogger(MusicGenerationService.class);
    private final GenerationTaskRepository tasks;
    private final GenerationChatProjectionPort projection;
    private final MusicProviderPort provider;
    private final GeneratedArtifactStoragePort storage;
    private final MusicGenerationProperties properties;
    private final Executor executor;
    private final TransactionTemplate transaction;
    // Waiting work stays in the database rather than in an unbounded executor queue.
    private final Semaphore slots = new Semaphore(2);

    public MusicGenerationService(GenerationTaskRepository tasks, GenerationChatProjectionPort projection,
            MusicProviderPort provider, GeneratedArtifactStoragePort storage, MusicGenerationProperties properties,
            @Qualifier("musicGenerationExecutor") Executor executor, TransactionTemplate transaction) {
        this.tasks = tasks;
        this.projection = projection;
        this.provider = provider;
        this.storage = storage;
        this.properties = properties;
        this.executor = executor;
        this.transaction = transaction;
    }

    public SubmitGenerationResult submit(Long user, String session, MusicGenerationSpec spec) {
        return transaction.execute(status -> {
            Instant now = Instant.now();
            GenerationTask task = GenerationTask.create(UUID.randomUUID().toString(), user, session, spec, now);
            tasks.save(task);
            // Checks ownership before allowing the background worker to make a paid request.
            task.bindChatMessage(projection.createPendingMessage(task), now);
            task.queueMusic(now);
            tasks.save(task);
            projection.updateMessage(task);
            return new SubmitGenerationResult(task.id(), task.providerTaskId(), task.chatMessageId());
        });
    }

    public Map<String, Object> query(Long user, String session, String id) {
        GenerationTask task = tasks.findById(id)
                .filter(t -> t.userId().equals(user) && t.sessionId().equals(session) && t.spec() instanceof MusicGenerationSpec)
                .orElseThrow(() -> new IllegalArgumentException("歌曲任务不存在"));
        return Map.of("taskId", task.id(), "status", task.status().name(),
                "messageId", task.chatMessageId() == null ? "" : task.chatMessageId().toString(),
                "lyrics", task.resultText() == null ? "" : task.resultText(),
                "failureMessage", task.failureMessage() == null ? "" : task.failureMessage());
    }

    /** Called by the durable task poller. No provider request runs on the scheduler thread. */
    public void dispatch(GenerationTask task, Instant now) {
        if (task.status().isTerminal()) return;
        if (!"QUEUED".equals(task.providerStatus())) {
            task.musicResultUnknown("执行中断或超时，无法确认生成结果；不会自动重复生成，请确认后手动重试", now);
            persistWithMessage(task);
            return;
        }
        if (!slots.tryAcquire()) return;
        try {
            // Optimistic versioning makes this claim exclusive across backend instances.
            task.startMusic(now.plus(properties.getRequestTimeout().multipliedBy(2)).plusSeconds(300), now);
            tasks.save(task);
        } catch (RuntimeException ex) {
            slots.release();
            throw ex;
        }
        try {
            executor.execute(() -> {
                try { generate(task); }
                finally { slots.release(); }
            });
        } catch (RuntimeException ex) {
            slots.release();
            finishFailure(task.id(), "后台执行器不可用，歌曲尚未生成，请稍后手动重试", false);
        }
    }

    private void generate(GenerationTask task) {
        try {
            requireActiveClaim(task);
            MusicGenerationSpec spec = (MusicGenerationSpec) task.spec();
            String lyrics = spec.lyrics();
            String prompt = spec.prompt();
            if (lyrics == null) {
                MusicProviderPort.Lyrics written = provider.writeLyrics(spec);
                lyrics = written.lyrics();
                // User instructions have priority over generated style suggestions.
                String combined = prompt + "\n风格建议：" + written.styleTags();
                if (combined.codePointCount(0, combined.length()) <= 2000) prompt = combined;
            }
            task.recordMusicLyrics(lyrics, Instant.now());
            tasks.save(task);
            requireActiveClaim(task);
            byte[] mp3 = provider.sing(lyrics, prompt);
            task.startMaterialization(task.providerTaskId(), Instant.now());
            persistWithMessage(task);
            storage.storeAudio(task.sessionId(), mp3, artifact -> {
                task.complete(artifact, Instant.now());
                tasks.save(task);
                projection.updateMessage(task);
                return null;
            });
        } catch (ConcurrencyFailureException ex) {
            log.debug("Ignoring stale music worker for {}", task.id());
        } catch (MusicProviderException ex) {
            finishFailure(task.id(), ex.getMessage(), ex.resultUnknown());
        } catch (IllegalArgumentException ex) {
            finishFailure(task.id(), ex.getMessage(), false);
        } catch (RuntimeException ex) {
            // A storage/DB exception may contain credentials, paths or provider payloads.
            finishFailure(task.id(), "歌曲生成或保存失败；不会自动重复生成，请稍后手动重试", false);
        }
    }

    private void requireActiveClaim(GenerationTask claimed) {
        GenerationTask current = tasks.findById(claimed.id()).orElseThrow();
        if (current.version() != claimed.version() || current.status() != GenerationStatus.IN_PROGRESS
                || current.nextPollAt() == null || !Instant.now().isBefore(current.nextPollAt())) {
            throw new ConcurrencyFailureException("歌曲任务的执行租约已失效");
        }
    }

    private void finishFailure(String id, String message, boolean unknown) {
        try {
            GenerationTask task = tasks.findById(id).orElseThrow();
            if (task.status().isTerminal()) return;
            if (unknown) task.musicResultUnknown(message, Instant.now());
            else task.fail(message, Instant.now());
            persistWithMessage(task);
        } catch (RuntimeException ex) {
            // Its persisted lease will be recovered by dispatch without repeating the paid request.
            log.warn("Music task {} could not persist its failure; awaiting lease recovery", id);
        }
    }

    private void persistWithMessage(GenerationTask task) {
        transaction.executeWithoutResult(status -> {
            tasks.save(task);
            projection.updateMessage(task);
        });
    }
}
