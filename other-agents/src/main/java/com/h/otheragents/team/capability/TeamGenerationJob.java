package com.h.otheragents.team.capability;

import com.h.capabilities.generation.application.port.in.PollDueGenerationTasksUseCase;
import com.h.capabilities.generation.infrastructure.config.GenerationProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Worker 侧视频任务轮询：只领取本服务提交的任务（generation_tasks.owner 隔离），
 * 终态经 Matrix 投影按 task.actor() 发回原房间。本服务无 @EnableScheduling，
 * 用独立调度线程驱动（与 TeamDocumentJob 同模式）。
 */
public class TeamGenerationJob implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(TeamGenerationJob.class);

    private final PollDueGenerationTasksUseCase pollDueTasks;
    private final GenerationProperties properties;
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(
                    runnable -> Thread.ofPlatform().name("team-generation-scheduler").daemon(true).unstarted(runnable));

    public TeamGenerationJob(PollDueGenerationTasksUseCase pollDueTasks, GenerationProperties properties) {
        this.pollDueTasks = pollDueTasks;
        this.properties = properties;
    }

    public void start() {
        long delayMillis = Math.max(1000, properties.getPolling().getFixedDelay().toMillis());
        scheduler.scheduleWithFixedDelay(this::tick, delayMillis, delayMillis, TimeUnit.MILLISECONDS);
        log.info("Team generation job started owner={}", properties.getOwner());
    }

    private void tick() {
        try {
            pollDueTasks.execute();
        } catch (RuntimeException failure) {
            log.error("Team generation polling failed", failure);
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}