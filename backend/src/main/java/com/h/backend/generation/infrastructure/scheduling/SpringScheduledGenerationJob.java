package com.h.backend.generation.infrastructure.scheduling;

import com.h.backend.generation.application.port.in.PollDueGenerationTasksUseCase;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Component
@ConditionalOnProperty(prefix = "generation.polling", name = "enabled", havingValue = "true", matchIfMissing = true)
public class SpringScheduledGenerationJob {
    private static final Logger log = LoggerFactory.getLogger(SpringScheduledGenerationJob.class);

    private final PollDueGenerationTasksUseCase pollDueGenerationTasksUseCase;

    public SpringScheduledGenerationJob(PollDueGenerationTasksUseCase pollDueGenerationTasksUseCase) {
        this.pollDueGenerationTasksUseCase = pollDueGenerationTasksUseCase;
    }

    @Scheduled(fixedDelayString = "${generation.polling.fixed-delay:5s}")
    public void pollDueTasks() {
        log.debug("Starting due generation task polling");
        pollDueGenerationTasksUseCase.execute();
    }
}
