package com.h.backend.memory;

import com.h.backend.memory.domain.CompletedTurn;
import com.h.backend.memory.domain.MemoryInvocationContext;
import com.h.backend.memory.domain.MemoryRecallCommand;
import com.h.backend.memory.domain.MemoryRecallResult;
import com.h.backend.memory.domain.MemoryScopeKind;
import com.h.backend.memory.domain.MemoryScopePolicy;
import com.h.backend.memory.infrastructure.LongTermMemoryRuntimeImpl;
import com.h.backend.memory.infrastructure.config.LongTermMemoryProperties;
import com.h.backend.memory.infrastructure.mem0.InMemoryMem0Gateway;
import com.h.backend.memory.infrastructure.mem0.Mem0Gateway;
import com.h.backend.memory.infrastructure.mem0.Mem0Models;
import com.h.backend.memory.infrastructure.persistence.entity.MemoryCaptureOutboxEntity;
import com.h.backend.memory.infrastructure.persistence.mapper.MemoryCaptureOutboxMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LongTermMemoryRuntimeImplTest {

    private final InMemoryMem0Gateway gateway = new InMemoryMem0Gateway();
    private final MemoryCaptureOutboxMapper outboxMapper = mock(MemoryCaptureOutboxMapper.class);
    private LongTermMemoryProperties properties;

    private final MemoryInvocationContext context = new MemoryInvocationContext(
            7L, "export-assistant", "session-root", 99L, "session-actual", null);

    @BeforeEach
    void setUp() {
        properties = new LongTermMemoryProperties();
        properties.getRecall().setTopKPerScope(4);
        properties.getRecall().setMaxTotalResults(4);
        properties.getRecall().setMaxChars(6000);
        properties.getRecall().setCircuitBreakerEnabled(false);
    }

    private LongTermMemoryRuntimeImpl runtime() {
        return new LongTermMemoryRuntimeImpl(gateway, properties, outboxMapper);
    }

    private void addMemory(MemoryScopeKind kind, String text) {
        addMemory(gateway, kind, text);
    }

    private void addMemory(Mem0Gateway target, MemoryScopeKind kind, String text) {
        target.add(new Mem0Models.Mem0AddCommand(
                MemoryScopePolicy.toOwnerScope(context, kind),
                List.of(Mem0Models.Mem0Message.user(text)),
                false,
                Map.of()));
    }

    @Test
    void recallMergesAllScopesAndDeduplicates() {
        addMemory(MemoryScopeKind.USER, "用户偏好深色主题");
        addMemory(MemoryScopeKind.USER, "用户偏好深色主题");
        addMemory(MemoryScopeKind.AGENT, "该用户在本 Agent 讨论过部署流程");
        addMemory(MemoryScopeKind.RUN, "本次任务正在调试登录问题");

        MemoryRecallResult result = runtime().recall(new MemoryRecallCommand(
                context,
                java.util.Set.of(MemoryScopeKind.USER, MemoryScopeKind.AGENT, MemoryScopeKind.RUN),
                "主题"));

        assertEquals(3, result.items().size());
        assertTrue(result.items().stream()
                .anyMatch(item -> item.scopeKind() == MemoryScopeKind.USER));
        assertTrue(result.items().stream()
                .anyMatch(item -> item.scopeKind() == MemoryScopeKind.AGENT));
        assertTrue(result.items().stream()
                .anyMatch(item -> item.scopeKind() == MemoryScopeKind.RUN));
    }

    @Test
    void recallRespectsMaxTotalBudget() {
        for (int i = 0; i < 6; i++) {
            addMemory(MemoryScopeKind.USER, "用户偏好记录 " + i);
        }

        MemoryRecallResult result = runtime().recall(new MemoryRecallCommand(
                context, java.util.Set.of(MemoryScopeKind.USER), "记录"));

        assertEquals(4, result.items().size());
    }

    @Test
    void recallFailsOpenWhenGatewayErrors() {
        AtomicInteger failures = new AtomicInteger();
        Mem0Gateway failingGateway = new InMemoryMem0Gateway() {
            @Override
            public List<Mem0Models.Mem0Memory> searchExact(Mem0Models.Mem0SearchQuery query) {
                if (query.scope().scopeKind() == MemoryScopeKind.AGENT) {
                    failures.incrementAndGet();
                    throw new IllegalStateException("simulated gateway failure");
                }
                return super.searchExact(query);
            }
        };
        LongTermMemoryRuntimeImpl runtime =
                new LongTermMemoryRuntimeImpl(failingGateway, properties, outboxMapper);
        addMemory(failingGateway, MemoryScopeKind.USER, "用户偏好深色主题");
        addMemory(failingGateway, MemoryScopeKind.AGENT, "该用户在本 Agent 讨论过部署流程");
        addMemory(failingGateway, MemoryScopeKind.RUN, "本次任务正在调试登录问题");

        MemoryRecallResult result = runtime.recall(new MemoryRecallCommand(
                context,
                java.util.Set.of(MemoryScopeKind.USER, MemoryScopeKind.AGENT, MemoryScopeKind.RUN),
                "主题"));

        assertEquals(1, failures.get());
        assertEquals(2, result.items().size());
    }

    @Test
    void recallReturnsEmptyForBlankQuery() {
        addMemory(MemoryScopeKind.USER, "用户偏好深色主题");

        MemoryRecallResult result = runtime().recall(new MemoryRecallCommand(
                context, java.util.Set.of(MemoryScopeKind.USER), "  "));

        assertTrue(result.items().isEmpty());
    }

    @Test
    void recallIsolatedBetweenUsers() {
        addMemory(MemoryScopeKind.USER, "用户七的记忆");
        MemoryInvocationContext otherUser = new MemoryInvocationContext(
                8L, "export-assistant", "session-root", 100L, "session-actual", null);
        gateway.add(new Mem0Models.Mem0AddCommand(
                MemoryScopePolicy.toOwnerScope(otherUser, MemoryScopeKind.USER),
                List.of(Mem0Models.Mem0Message.user("用户八的记忆")),
                false,
                Map.of()));

        MemoryRecallResult result = runtime().recall(new MemoryRecallCommand(
                context, java.util.Set.of(MemoryScopeKind.USER), "记忆"));

        assertEquals(1, result.items().size());
        assertEquals("用户七的记忆", result.items().get(0).text());
    }

    @Test
    void stageCaptureEnqueuesOncePerTurnKey() {
        // 第一次查无记录 → 入队；第二次按 operation key 命中已有记录 → 跳过
        when(outboxMapper.selectByOperationKey(any()))
                .thenReturn(null, new MemoryCaptureOutboxEntity());
        CompletedTurn turn = new CompletedTurn(context, 11L, 22L, MemoryScopeKind.RUN);

        runtime().stageCapture(turn);
        runtime().stageCapture(turn);

        verify(outboxMapper, times(1)).insert(any(MemoryCaptureOutboxEntity.class));
    }

    @Test
    void stageCaptureDuplicateKeyIgnored() {
        when(outboxMapper.selectByOperationKey(any())).thenReturn(null);
        when(outboxMapper.insert(any(MemoryCaptureOutboxEntity.class)))
                .thenThrow(new DuplicateKeyException("duplicate"));
        CompletedTurn turn = new CompletedTurn(context, 11L, 22L, MemoryScopeKind.RUN);

        runtime().stageCapture(turn);
    }
}
