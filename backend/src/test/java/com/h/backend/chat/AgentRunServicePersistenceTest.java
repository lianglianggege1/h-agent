package com.h.backend.chat;

import com.h.backend.chat.entity.AgentRunEntity;
import com.h.backend.chat.mapper.AgentRunMapper;
import com.h.backend.chat.service.impl.AgentRunServiceImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class AgentRunServicePersistenceTest {

    @Test
    void shouldInsertAgentRunSkeletonWithExpectedFields() {
        AtomicReference<AgentRunEntity> insertedRef = new AtomicReference<>();
        AgentRunServiceImpl agentRunService = serviceWithMapper(entity -> {
            insertedRef.set(entity);
            entity.setId(42L);
        });

        var run = agentRunService.createRun(
                "session-123",
                1L,
                2L,
                3L,
                "gpt-test",
                "trace-456"
        );

        AgentRunEntity inserted = insertedRef.get();
        assertEquals(42L, run.id());
        assertNotNull(inserted);
        assertEquals("session-123", inserted.getSessionId());
        assertEquals(1L, inserted.getUserId());
        assertEquals(2L, inserted.getPromptId());
        assertEquals(3L, inserted.getUserMessageId());
        assertEquals("RUNNING", inserted.getStatus());
        assertEquals("gpt-test", inserted.getModelName());
        assertEquals("trace-456", inserted.getLangfuseTraceId());
        assertEquals(0, inserted.getToolCount());
        assertEquals("[]", inserted.getToolNamesJson());
        assertNotNull(inserted.getStartedAt());
        assertNotNull(inserted.getCreatedAt());
        assertNotNull(inserted.getUpdatedAt());
    }

    @Test
    void shouldReturnNullIdWhenMapperDoesNotPopulateGeneratedId() {
        AgentRunServiceImpl agentRunService = serviceWithMapper(entity -> {
        });

        var run = agentRunService.createRun(
                "session-123",
                1L,
                2L,
                3L,
                "gpt-test",
                null
        );

        assertNull(run.id());
    }

    private AgentRunServiceImpl serviceWithMapper(Consumer<AgentRunEntity> onInsert) {
        AgentRunMapper agentRunMapper = (AgentRunMapper) Proxy.newProxyInstance(
                AgentRunMapper.class.getClassLoader(),
                new Class[]{AgentRunMapper.class},
                (proxy, method, args) -> {
                    if ("insert".equals(method.getName())) {
                        AgentRunEntity entity = (AgentRunEntity) args[0];
                        onInsert.accept(entity);
                        return 1;
                    }
                    throw new UnsupportedOperationException("Unexpected mapper method: " + method.getName());
                }
        );
        return new AgentRunServiceImpl(agentRunMapper);
    }
}
