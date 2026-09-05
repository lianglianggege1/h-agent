package com.h.backend.automation.application;

import com.h.backend.automation.domain.AutomationRun;
import com.h.backend.automation.domain.AutomationRuntime;
import com.h.backend.automation.domain.AutomationTask;
import com.h.backend.chat.domain.agent.AgentDefinition;
import com.h.backend.chat.domain.agent.AgentRegistry;
import com.h.backend.chat.domain.agent.AgentRuntimeType;
import com.h.backend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutomationTaskServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    @Test
    void derivesLangChainRuntimeAndComputesNextRun() {
        InMemoryRepository repository = new InMemoryRepository();
        AutomationTaskService service = service(repository);

        AutomationTask task = service.create(7L, new AutomationTaskCommand(
                "晨报", "汇总今天的行业动态", "standard-chat", null,
                "0 0 9 * * *", "Asia/Shanghai", true
        ), "CHAT_LANGCHAIN4J");

        assertEquals(AutomationRuntime.LANGCHAIN4J, task.runtime());
        assertEquals(Instant.parse("2026-09-05T01:00:00Z"), task.nextRunAt());
        assertEquals("CHAT_LANGCHAIN4J", task.createdVia());
    }

    @Test
    void derivesAgentScopeRuntimeForHarness() {
        AutomationTask task = service(new InMemoryRepository()).create(7L, new AutomationTaskCommand(
                "协作复盘", "复盘项目", "harness-agent", null,
                "0 0 18 * * 5", "Asia/Shanghai", true
        ), "CHAT_AGENTSCOPE");

        assertEquals(AutomationRuntime.AGENTSCOPE, task.runtime());
    }

    @Test
    void rejectsRuntimeThatDoesNotMatchSelectedAgent() {
        AutomationTaskService service = service(new InMemoryRepository());

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(
                7L,
                new AutomationTaskCommand("错误任务", "执行", "harness-agent", AutomationRuntime.LANGCHAIN4J,
                        "0 0 9 * * *", "Asia/Shanghai", true),
                "UI"
        ));

        assertEquals(40033, error.getCode());
    }

    private static AutomationTaskService service(InMemoryRepository repository) {
        AgentRegistry registry = new AgentRegistry(List.of(
                new AgentDefinition("standard-chat", "普通聊天", "通用", List.of(), "", new Object(),
                        AgentRuntimeType.STANDARD_STREAMING_CHAT, true),
                new AgentDefinition("harness-agent", "协作 Agent", "协作", List.of(), "", new Object(),
                        AgentRuntimeType.HARNESS_STREAMING, true)
        ));
        return new AutomationTaskService(repository, registry, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static final class InMemoryRepository implements AutomationTaskRepository {
        private final List<AutomationTask> tasks = new ArrayList<>();

        @Override
        public AutomationTask insert(AutomationTask task) {
            tasks.add(task);
            return task;
        }

        @Override
        public AutomationTask updateOwned(Long userId, String taskId, long expectedRevision, AutomationTask replacement) {
            return null;
        }

        @Override
        public Optional<AutomationTask> findOwned(Long userId, String taskId) {
            return tasks.stream().filter(task -> task.userId().equals(userId) && task.id().equals(taskId)).findFirst();
        }

        @Override
        public List<AutomationTask> listOwned(Long userId) {
            return tasks;
        }

        @Override
        public boolean softDeleteOwned(Long userId, String taskId) {
            return false;
        }

        @Override
        public List<AutomationTask> claimDue(Instant now, int limit, String leaseOwner, Duration leaseDuration) {
            return List.of();
        }

        @Override
        public void releaseLease(String taskId, String leaseOwner, Instant nextRunAt, Instant lastRunAt, String lastStatus) {
        }

        @Override
        public void recordManualRunResult(String taskId, Instant lastRunAt, String lastStatus) {
        }

        @Override
        public AutomationRun insertRun(AutomationRun run) {
            return run;
        }

        @Override
        public void completeRun(String runId, String status, Instant finishedAt, String sessionId, String output, String errorMessage) {
        }

        @Override
        public List<AutomationRun> listRunsOwned(Long userId, String taskId, int limit) {
            return List.of();
        }
    }
}
