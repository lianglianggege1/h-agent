package com.h.backend.chat;

import com.h.backend.chat.entity.ChatMemorySnapshotEntity;
import com.h.backend.chat.entity.ChatSessionEntity;
import com.h.backend.chat.mapper.ChatMemorySnapshotMapper;
import com.h.backend.chat.mapper.ChatSessionMapper;
import com.h.backend.chat.memory.ChatMemoryContext;
import com.h.backend.chat.service.impl.ChatMemorySnapshotServiceImpl;
import com.h.backend.utils.RedisUtil;
import com.h.backend.utils.RedissonUtil;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatMemorySnapshotServiceImplTest {

    @Test
    void restoreToRedisDoesNotSkipScopedSnapshotsWhenDefaultScopeIsCached() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        RedissonUtil redissonUtil = mock(RedissonUtil.class);
        ChatSessionMapper chatSessionMapper = mock(ChatSessionMapper.class);
        ChatMemorySnapshotMapper snapshotMapper = mock(ChatMemorySnapshotMapper.class);
        ChatMemorySnapshotServiceImpl service = new ChatMemorySnapshotServiceImpl(
                redisUtil,
                redissonUtil,
                chatSessionMapper,
                snapshotMapper
        );
        ChatSessionEntity session = new ChatSessionEntity();
        session.setUserId(1L);
        session.setPromptId(null);
        session.setSessionId("s1");
        session.setAgentId("car-rental-assistant");
        ChatMemorySnapshotEntity defaultSnapshot = snapshot("s1", "car-rental-assistant", "default", "[]", 3L);
        ChatMemorySnapshotEntity scopedSnapshot = snapshot("s1", "car-rental-assistant", "customer-info-extractor", "[]", 7L);
        when(chatSessionMapper.selectBySessionId("s1")).thenReturn(session);
        when(snapshotMapper.selectAllBySessionId("s1")).thenReturn(List.of(defaultSnapshot, scopedSnapshot));
        when(redisUtil.get("chat:memory:1:s1:car-rental-assistant:default", String.class)).thenReturn("cached");
        doAnswer(invocation -> {
            Runnable runnable = invocation.getArgument(3);
            runnable.run();
            return null;
        }).when(redissonUtil).executeWithLock(eq("chat:memory:lock:s1"), eq(3L), eq(TimeUnit.SECONDS), any(Runnable.class));

        service.restoreToRedis("s1");

        verify(redisUtil).set("chat:memory:1:s1:car-rental-assistant:customer-info-extractor", "[]", 86_400L);
        verify(redisUtil).set("chat:memory:version:1:s1:car-rental-assistant:customer-info-extractor", 7L, 86_400L);
    }

    @Test
    void deleteSnapshotCanDeleteOneScopedMemory() {
        RedisUtil redisUtil = mock(RedisUtil.class);
        RedissonUtil redissonUtil = mock(RedissonUtil.class);
        ChatMemorySnapshotMapper snapshotMapper = mock(ChatMemorySnapshotMapper.class);
        ChatMemorySnapshotServiceImpl service = new ChatMemorySnapshotServiceImpl(
                redisUtil,
                redissonUtil,
                mock(ChatSessionMapper.class),
                snapshotMapper
        );
        ChatMemoryContext context = new ChatMemoryContext(
                1L,
                null,
                "s1",
                "car-rental-assistant",
                "customer-info-extractor"
        );

        service.deleteSnapshot(context);

        verify(redisUtil).delete(
                "chat:memory:1:s1:car-rental-assistant:customer-info-extractor",
                "chat:memory:version:1:s1:car-rental-assistant:customer-info-extractor",
                "chat:memory:dirty:1:s1:car-rental-assistant:customer-info-extractor"
        );
        verify(snapshotMapper).deleteBySessionScope("s1", "car-rental-assistant", "customer-info-extractor");
    }

    private static ChatMemorySnapshotEntity snapshot(
            String sessionId,
            String agentId,
            String memoryScope,
            String payload,
            Long version
    ) {
        ChatMemorySnapshotEntity snapshot = new ChatMemorySnapshotEntity();
        snapshot.setUserId(1L);
        snapshot.setPromptId(null);
        snapshot.setSessionId(sessionId);
        snapshot.setAgentId(agentId);
        snapshot.setMemoryScope(memoryScope);
        snapshot.setMemoryPayloadJson(payload);
        snapshot.setSnapshotVersion(version);
        return snapshot;
    }
}
