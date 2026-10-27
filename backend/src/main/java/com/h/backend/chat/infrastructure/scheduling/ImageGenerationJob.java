package com.h.backend.chat.infrastructure.scheduling;

import com.h.backend.chat.application.impl.ImageGenerationTaskService;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(prefix = "image-generation.jobs", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ImageGenerationJob {
    private static final Logger log = LoggerFactory.getLogger(ImageGenerationJob.class);
    private final ImageGenerationTaskService tasks;
    private final long staleAfterSeconds;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofPlatform().name("image-generation-worker").factory());

    public ImageGenerationJob(ImageGenerationTaskService tasks,
                              @Value("${image-generation.jobs.stale-after-seconds:3600}") long staleAfterSeconds) {
        this.tasks = tasks;
        this.staleAfterSeconds = Math.max(3600, staleAfterSeconds);
    }

    @Scheduled(fixedDelayString = "${image-generation.jobs.fixed-delay:1s}")
    public void generateNext() {
        if (!running.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                try {
                    tasks.processNext();
                } catch (RuntimeException exception) {
                    log.warn("Image generation worker failed", exception);
                } finally {
                    running.set(false);
                }
            });
        } catch (RuntimeException exception) {
            running.set(false);
            throw exception;
        }
    }

    @Scheduled(fixedDelay = 60000)
    public void recoverStale() {
        tasks.recoverStale(staleAfterSeconds);
    }

    @PreDestroy
    public void stop() {
        worker.shutdownNow();
    }
}
