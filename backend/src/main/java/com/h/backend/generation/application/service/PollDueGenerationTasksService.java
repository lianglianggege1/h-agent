package com.h.backend.generation.application.service;

import com.h.backend.generation.application.port.in.PollDueGenerationTasksUseCase;
import com.h.backend.generation.application.port.out.GenerationChatProjectionPort;
import com.h.backend.generation.application.port.out.GenerationTaskRepository;
import com.h.backend.generation.application.port.out.ProviderTaskQueryPort;
import com.h.backend.generation.application.port.out.ProviderTaskRejectedException;
import com.h.backend.generation.domain.model.GenerationStatus;
import com.h.backend.generation.domain.model.GenerationTask;
import com.h.backend.generation.infrastructure.config.GenerationProperties;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

@Service
public class PollDueGenerationTasksService implements PollDueGenerationTasksUseCase {
    private static final Logger log = LoggerFactory.getLogger(PollDueGenerationTasksService.class);

    private final GenerationTaskRepository taskRepository;
    private final ProviderTaskQueryPort providerTaskQueryPort;
    private final MaterializeGeneratedArtifactService materializeService;
    private final GenerationChatProjectionPort chatProjectionPort;
    private final GenerationProperties properties;
    private final Clock clock;
    @Autowired private VideoV2Service videoV2Service;

    @Autowired
    public PollDueGenerationTasksService(
            GenerationTaskRepository taskRepository,
            ProviderTaskQueryPort providerTaskQueryPort,
            MaterializeGeneratedArtifactService materializeService,
            GenerationChatProjectionPort chatProjectionPort,
            GenerationProperties properties) {
        this(
                taskRepository,
                providerTaskQueryPort,
                materializeService,
                chatProjectionPort,
                properties,
                Clock.systemUTC());
    }

    PollDueGenerationTasksService(
            GenerationTaskRepository taskRepository,
            ProviderTaskQueryPort providerTaskQueryPort,
            MaterializeGeneratedArtifactService materializeService,
            GenerationChatProjectionPort chatProjectionPort,
            GenerationProperties properties,
            Clock clock) {
        this.taskRepository = taskRepository;
        this.providerTaskQueryPort = providerTaskQueryPort;
        this.materializeService = materializeService;
        this.chatProjectionPort = chatProjectionPort;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public void execute() {
        Instant now = clock.instant();
        for (GenerationTask stale :
                taskRepository.findStaleSubmissions(
                        now.minus(Duration.ofMinutes(2)), properties.getPolling().getBatchSize())) {
            try {
                stale.submissionUnknown("执行中断，创建请求结果待确认，请勿自动重复提交", now);
                taskRepository.save(stale);
                if (stale.chatMessageId() == null) {
                    stale.bindChatMessage(chatProjectionPort.createPendingMessage(stale), now);
                    taskRepository.save(stale);
                }
                chatProjectionPort.updateMessage(stale);
            } catch (org.springframework.dao.ConcurrencyFailureException ignored) {
                /* The submitting worker won the race. */
            }
        }
        List<GenerationTask> tasks =
                taskRepository.findDue(now, properties.getPolling().getBatchSize());
        if (tasks.isEmpty()) {
            log.debug("No due generation tasks at {}", now);
            return;
        }
        log.info(
                "Polling {} due generation task(s) at {}: {}",
                tasks.size(),
                now,
                tasks.stream().map(GenerationTask::id).toList());
        for (GenerationTask task : tasks) {
            try {
                processOne(task, now);
            } catch (org.springframework.dao.ConcurrencyFailureException ignored) {
                log.debug("Ignoring stale generation update for {}", task.id());
            }
        }
    }

    @Transactional
    void processOne(GenerationTask task, Instant now) {
        try {
            if (task.spec() instanceof com.h.backend.generation.domain.model.VideoV2Spec
                    && task.resultText() == null
                    && !now.isBefore(task.createdAt().plus(Duration.ofDays(7)))) {
                task.fail("已超出供应商7日任务查询窗口", now);
                taskRepository.save(task);
                chatProjectionPort.updateMessage(task);
                return;
            }
            if (task.status() == GenerationStatus.MATERIALIZING
                    || task.status() == GenerationStatus.RETRY_WAIT
                            && task.providerFileId() != null) {
                materializeService.execute(task, now);
                return;
            }
            if (task.status() == GenerationStatus.IN_PROGRESS
                    || task.status() == GenerationStatus.RETRY_WAIT) {
                processProviderStatus(task, now);
            }
        } catch (org.springframework.dao.ConcurrencyFailureException ex) {
            log.debug(
                    "Generation task {} already changed; ignoring stale polling result", task.id());
        } catch (IllegalArgumentException ex) {
            task.fail(safeMessage(ex), now);
            taskRepository.save(task);
            chatProjectionPort.updateMessage(task);
        } catch (ProviderTaskRejectedException ex) {
            task.fail(safeMessage(ex), now);
            taskRepository.save(task);
            chatProjectionPort.updateMessage(task);
            log.warn("Generation task {} rejected by provider: {}", task.id(), ex.getMessage());
        } catch (RuntimeException ex) {
            task.retry(safeMessage(ex), nextRetryAt(task, now), now);
            taskRepository.save(task);
            chatProjectionPort.updateMessage(task);
            log.warn("Failed to process generation task {}", task.id(), ex);
        }
    }

    private void processProviderStatus(GenerationTask task, Instant now) {
        if (task.spec() instanceof com.h.backend.generation.domain.model.VideoV2Spec enhanced
                && enhanced.generateAfterEnhancement()
                && task.resultText() != null) {
            completeEnhancement(task, task.resultText(), now);
            return;
        }
        ProviderTaskQueryPort.ProviderTaskStatus providerStatus =
                providerTaskQueryPort.query(task.providerTaskId());
        switch (providerStatus.status()) {
            case PREPARING, QUEUEING, PROCESSING -> {
                task.recordProviderProgress(
                        providerStatus.status().name(),
                        now.plus(progressDelay(providerStatus.status())),
                        now);
                taskRepository.save(task);
                chatProjectionPort.updateMessage(task);
            }
            case CANCELLED -> {
                task.cancel(now);
                taskRepository.save(task);
                chatProjectionPort.updateMessage(task);
            }
            case SUCCESS -> {
                if (task.spec() instanceof com.h.backend.generation.domain.model.VideoV2Spec v
                        && v.taskType().equals("h3_context_ir")
                        && providerStatus.resultPrompt() == null)
                    throw new ProviderTaskRejectedException(502, "提示词增强任务未返回文本结果");
                if (providerStatus.resultPrompt() != null) {
                    completeEnhancement(task, providerStatus.resultPrompt(), now);
                    return;
                }
                task.startMaterialization(providerStatus.fileId(), now);
                taskRepository.save(task);
                chatProjectionPort.updateMessage(task);
                materializeService.execute(task, now);
            }
            case FAILED -> {
                task.fail(
                        providerStatus.failureMessage() == null
                                ? "视频生成失败"
                                : providerStatus.failureMessage(),
                        now);
                taskRepository.save(task);
                chatProjectionPort.updateMessage(task);
            }
        }
    }

    private void completeEnhancement(GenerationTask task, String prompt, Instant now) {
        if (task.spec() instanceof com.h.backend.generation.domain.model.VideoV2Spec spec
                && spec.generateAfterEnhancement()) {
            if (task.resultText() == null) {
                task.recordEnhancedPrompt(prompt, now);
                taskRepository.save(task);
            }
            videoV2Service.generateFromEnhancement(task, prompt);
        }
        task.completeText(prompt, now);
        taskRepository.save(task);
        chatProjectionPort.updateMessage(task);
    }

    private Duration progressDelay(ProviderTaskQueryPort.ProviderTaskStatus.Status status) {
        return status == ProviderTaskQueryPort.ProviderTaskStatus.Status.PROCESSING
                ? properties.getPolling().getProcessingDelay()
                : properties.getPolling().getQueueingDelay();
    }

    private Instant nextRetryAt(GenerationTask task, Instant now) {
        int exponent = Math.min(task.retryCount(), 2);
        return now.plus(properties.getPolling().getRetryDelay().multipliedBy(1L << exponent));
    }

    private String safeMessage(RuntimeException exception) {
        return exception.getMessage() == null ? "生成任务处理失败" : exception.getMessage();
    }
}
