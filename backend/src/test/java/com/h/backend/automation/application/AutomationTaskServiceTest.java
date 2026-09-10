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
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AutomationTaskServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-05T00:00:00Z");

    @Test
    void createsTaskDisabledEvenWhenLegacyClientRequestsEnabled() {
        InMemoryRepository repository = new InMemoryRepository();
        AutomationTaskService service = service(repository);

        AutomationTask task = service.create(7L, new AutomationTaskCommand(
                "晨报", "汇总今天的行业动态", "standard-chat", null,
                "0 0 9 * * *", "Asia/Shanghai", true
        ), "CHAT_LANGCHAIN4J", "session-1");

        assertEquals(AutomationRuntime.LANGCHAIN4J, task.runtime());
        assertFalse(task.enabled());
        assertNull(task.nextRunAt());
        assertEquals("CHAT_LANGCHAIN4J", task.createdVia());
    }

    @Test
    void derivesAgentScopeRuntimeForHarness() {
        AutomationTask task = service(new InMemoryRepository()).create(7L, new AutomationTaskCommand(
                "协作复盘", "复盘项目", "harness-agent", null,
                "0 0 18 * * 5", "Asia/Shanghai", true
        ), "CHAT_AGENTSCOPE", "session-1");

        assertEquals(AutomationRuntime.AGENTSCOPE, task.runtime());
    }

    @Test
    void rejectsRuntimeThatDoesNotMatchSelectedAgent() {
        AutomationTaskService service = service(new InMemoryRepository());

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(
                7L,
                new AutomationTaskCommand("错误任务", "执行", "harness-agent", AutomationRuntime.LANGCHAIN4J,
                        "0 0 9 * * *", "Asia/Shanghai", true),
                "UI", "session-1"
        ));

        assertEquals(40033, error.getCode());
    }

    @Test
    void rejectsSessionDeliveryWithoutATargetSession() {
        AutomationTaskService service = service(new InMemoryRepository());

        BusinessException error = assertThrows(BusinessException.class, () -> service.create(
                7L,
                new AutomationTaskCommand(
                        "晨报", "汇总今天的行业动态", "standard-chat", null,
                        "0 0 9 * * *", "Asia/Shanghai", false, "SESSION", null),
                "UI", "session-1"
        ));

        assertEquals(40034, error.getCode());
    }

    @Test
    void rejectsEnablingAFourthTaskForTheSameUser() {
        InMemoryRepository repository = new InMemoryRepository();
        AutomationTaskService service = service(repository);
        AutomationTask first = createDisabled(service, "晨报");
        AutomationTask second = createDisabled(service, "午报");
        AutomationTask third = createDisabled(service, "晚报");
        AutomationTask fourth = createDisabled(service, "周报");

        service.enable(7L, first.id(), first.revision());
        service.enable(7L, second.id(), second.revision());
        service.enable(7L, third.id(), third.revision());
        BusinessException error = assertThrows(BusinessException.class,
                () -> service.enable(7L, fourth.id(), fourth.revision()));

        assertEquals(40934, error.getCode());
        assertEquals("最多同时开启 3 个自动化任务，请先关闭一个任务后再试。", error.getMessage());
    }

    @Test
    void disablingTaskReleasesCapacityAndRepeatedCommandsAreIdempotent() {
        InMemoryRepository repository = new InMemoryRepository();
        AutomationTaskService service = service(repository);
        AutomationTask first = createDisabled(service, "晨报");
        AutomationTask second = createDisabled(service, "午报");
        AutomationTask third = createDisabled(service, "晚报");
        AutomationTask fourth = createDisabled(service, "周报");
        AutomationTask enabledFirst = service.enable(7L, first.id(), first.revision());
        service.enable(7L, second.id(), second.revision());
        service.enable(7L, third.id(), third.revision());

        assertEquals(enabledFirst, service.enable(7L, first.id(), first.revision()));
        AutomationTask disabledFirst = service.disable(7L, first.id(), enabledFirst.revision());
        assertEquals(disabledFirst, service.disable(7L, first.id(), enabledFirst.revision()));
        AutomationTask enabledFourth = service.enable(7L, fourth.id(), fourth.revision());

        assertFalse(disabledFirst.enabled());
        assertEquals(Instant.parse("2026-09-05T01:00:00Z"), enabledFourth.nextRunAt());
    }

    private static AutomationTask createDisabled(AutomationTaskService service, String name) {
        return service.create(7L, new AutomationTaskCommand(
                name, "汇总今天的行业动态", "standard-chat", null,
                "0 0 9 * * *", "Asia/Shanghai", true
        ), "UI", "session-1");
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
        public AutomationTask enableOwned(
                Long userId,
                String taskId,
                long expectedRevision,
                Instant nextRunAt,
                Instant updatedAt,
                int maxEnabled
        ) {
            AutomationTask current = findOwned(userId, taskId).orElse(null);
            long enabledCount = tasks.stream()
                    .filter(task -> task.userId().equals(userId) && task.enabled())
                    .count();
            if (current == null || current.revision() != expectedRevision || enabledCount >= maxEnabled) {
                return null;
            }
            AutomationTask enabled = withEnabled(current, true, nextRunAt, updatedAt);
            tasks.set(tasks.indexOf(current), enabled);
            return enabled;
        }

        @Override
        public AutomationTask disableOwned(
                Long userId,
                String taskId,
                long expectedRevision,
                Instant updatedAt
        ) {
            AutomationTask current = findOwned(userId, taskId).orElse(null);
            if (current == null || current.revision() != expectedRevision) {
                return null;
            }
            AutomationTask disabled = withEnabled(current, false, null, updatedAt);
            tasks.set(tasks.indexOf(current), disabled);
            return disabled;
        }

        @Override
        public Optional<AutomationTask> findOwned(Long userId, String taskId) {
            return tasks.stream().filter(task -> task.userId().equals(userId) && task.id().equals(taskId)).findFirst();
        }

        @Override
        public Optional<AutomationTask> findById(String taskId) {
            return tasks.stream().filter(task -> task.id().equals(taskId)).findFirst();
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
        public void recordRunResult(String taskId, Instant at, String status) {
        }

        @Override
        public AutomationRun insertRun(AutomationRun run) {
            return run;
        }

        @Override
        public AutomationRun insertManualRunIfNoActive(AutomationRun run) {
            return run;
        }

        @Override
        public AutomationRun insertScheduledRunIfAbsent(AutomationRun run) {
            return run;
        }

        @Override
        public boolean existsActiveRun(String taskId) {
            return false;
        }

        @Override
        public Optional<AutomationRun> findRunOwned(Long userId, String runId) {
            return Optional.empty();
        }

        @Override
        public Optional<AutomationRun> requestCancelRun(String runId, Instant now) {
            return Optional.empty();
        }

        @Override
        public List<AutomationRun> listRunsOwned(Long userId, String taskId, int limit) {
            return List.of();
        }

        private static AutomationTask withEnabled(
                AutomationTask task,
                boolean enabled,
                Instant nextRunAt,
                Instant updatedAt
        ) {
            return new AutomationTask(
                    task.id(), task.userId(), task.name(), task.instruction(), task.agentId(), task.runtime(),
                    task.schedule(), enabled, nextRunAt, task.lastRunAt(), task.lastStatus(),
                    task.createdVia(), task.revision() + 1, task.createdAt(), updatedAt,
                    task.deliverySink(), task.deliverySessionId(), task.sessionId()
            );
        }
    }
}
