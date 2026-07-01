package com.h.backend.chat.application.impl;

import com.baomidou.mybatisplus.core.toolkit.StringUtils;
import com.h.backend.chat.infrastructure.persistence.entity.ChatMemorySnapshotEntity;
import com.h.backend.chat.infrastructure.persistence.entity.ChatSessionEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatMemorySnapshotMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatSessionMapper;
import com.h.backend.chat.domain.memory.ChatMemoryContext;
import com.h.backend.chat.application.ChatMemorySnapshotService;
import com.h.backend.shared.infrastructure.utils.RedisUtil;
import com.h.backend.shared.infrastructure.utils.RedissonUtil;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
public class ChatMemorySnapshotServiceImpl implements ChatMemorySnapshotService {

    private static final long MEMORY_TTL_SECONDS = Duration.ofHours(24).getSeconds();
    private static final long FLUSH_DEBOUNCE_MILLIS = 1500L;
    private static final String MEMORY_FORMAT = "LANGCHAIN_WINDOW_V1";

    private final RedisUtil redisUtil;
    private final RedissonUtil redissonUtil;
    private final ChatSessionMapper chatSessionMapper;
    private final ChatMemorySnapshotMapper chatMemorySnapshotMapper;
    private final ScheduledExecutorService flushScheduler;
    private final ConcurrentMap<String, ScheduledFuture<?>> pendingFlushes = new ConcurrentHashMap<>();

    public ChatMemorySnapshotServiceImpl(
            RedisUtil redisUtil,
            RedissonUtil redissonUtil,
            ChatSessionMapper chatSessionMapper,
            ChatMemorySnapshotMapper chatMemorySnapshotMapper
    ) {
        this.redisUtil = redisUtil;
        this.redissonUtil = redissonUtil;
        this.chatSessionMapper = chatSessionMapper;
        this.chatMemorySnapshotMapper = chatMemorySnapshotMapper;
        this.flushScheduler = Executors.newSingleThreadScheduledExecutor(new SnapshotThreadFactory());
    }

    @Override
    public Optional<List<ChatMessage>> loadSnapshot(ChatMemoryContext context) {
        String cachedPayload = redisUtil.get(memoryKey(context), String.class);
        if (StringUtils.isNotBlank(cachedPayload)) {
            redisUtil.expire(memoryKey(context), MEMORY_TTL_SECONDS);
            redisUtil.expire(versionKey(context), MEMORY_TTL_SECONDS);
            Long currentVersion = readLong(versionKey(context));
            if (currentVersion == null) {
                ChatMemorySnapshotEntity snapshot = selectSnapshot(context);
                long seedVersion = snapshot == null || snapshot.getSnapshotVersion() == null ? 0L : snapshot.getSnapshotVersion();
                redisUtil.set(versionKey(context), seedVersion, MEMORY_TTL_SECONDS);
            }
            return Optional.of(ChatMessageDeserializer.messagesFromJson(cachedPayload));
        }

        ChatMemorySnapshotEntity snapshot = selectSnapshot(context);
        if (snapshot == null || StringUtils.isBlank(snapshot.getMemoryPayloadJson())) {
            return Optional.empty();
        }

        writeSnapshotToRedis(context, snapshot.getMemoryPayloadJson(), snapshot.getSnapshotVersion());
        return Optional.of(ChatMessageDeserializer.messagesFromJson(snapshot.getMemoryPayloadJson()));
    }

    @Override
    public void cacheMemory(ChatMemoryContext context, List<ChatMessage> messages) {
        String payload = ChatMessageSerializer.messagesToJson(messages);
        redissonUtil.executeWithLock(lockKey(context.sessionId()), 3, TimeUnit.SECONDS, () -> {
            long nextVersion = nextSnapshotVersion(context);
            writeSnapshotToRedis(context, payload, nextVersion);
            markDirty(context, nextVersion);
            scheduleFlush(context, messages, nextVersion);
        });
    }

    @Override
    public void deleteHotMemory(ChatMemoryContext context) {
        redissonUtil.executeWithLock(lockKey(context.sessionId()), 3, TimeUnit.SECONDS, () -> {
            redisUtil.delete(memoryKey(context), versionKey(context), dirtyKey(context));
            String resident = redisUtil.get(residentKey(context.userId()), String.class);
            if (context.sessionId().equals(resident)) {
                redisUtil.delete(residentKey(context.userId()));
            }
        });
    }

    @Override
    public void scheduleFlush(ChatMemoryContext context, List<ChatMessage> messages, long version) {
        String pendingKey = pendingKey(context);
        ScheduledFuture<?> previous = pendingFlushes.remove(pendingKey);
        if (previous != null) {
            previous.cancel(false);
        }
        ScheduledFuture<?> future = flushScheduler.schedule(
                () -> {
                    try {
                        flushNow(context);
                    } catch (RuntimeException ex) {
                        log.warn("异步刷新记忆快照失败，sessionId: {}, agentId: {}, scope: {}, version: {}",
                                context.sessionId(), context.agentId(), context.memoryScope(), version, ex);
                    } finally {
                        pendingFlushes.remove(pendingKey);
                    }
                },
                FLUSH_DEBOUNCE_MILLIS,
                TimeUnit.MILLISECONDS
        );
        pendingFlushes.put(pendingKey, future);
    }

    @Override
    public void flushNow(String sessionId) {
        ChatMemoryContext context = resolveContext(sessionId);
        if (context == null) {
            return;
        }
        flushNow(context);
        for (ChatMemorySnapshotEntity snapshot : chatMemorySnapshotMapper.selectAllBySessionId(sessionId)) {
            ChatMemoryContext scopedContext = contextFromSnapshot(snapshot);
            if (!scopedContext.equals(context)) {
                flushNow(scopedContext);
            }
        }
    }

    private void flushNow(ChatMemoryContext context) {
        redissonUtil.executeWithLock(lockKey(context), 3, TimeUnit.SECONDS, () -> {
            Long dirtyVersion = readLong(dirtyKey(context));
            if (dirtyVersion == null) {
                return;
            }

            String payload = redisUtil.get(memoryKey(context), String.class);
            if (StringUtils.isBlank(payload)) {
                return;
            }

            ChatSessionEntity session = chatSessionMapper.selectBySessionId(context.sessionId());
            ChatMemorySnapshotEntity entity = new ChatMemorySnapshotEntity();
            entity.setSessionRecordId(session == null ? null : session.getId());
            entity.setSessionId(context.sessionId());
            entity.setUserId(context.userId());
            entity.setPromptId(context.promptId());
            entity.setAgentId(context.agentId());
            entity.setMemoryScope(context.memoryScope());
            entity.setMemoryPayloadJson(payload);
            entity.setMemoryFormat(MEMORY_FORMAT);
            entity.setWindowSize(countMessages(payload));
            entity.setSourceMessageCount(resolveSourceMessageCount(session, entity.getWindowSize()));
            entity.setSnapshotVersion(dirtyVersion);
            entity.setLastCompactedAt(null);
            entity.setCreatedAt(LocalDateTime.now());
            entity.setUpdatedAt(LocalDateTime.now());

            if (entity.getSessionRecordId() == null) {
                return;
            }

            chatMemorySnapshotMapper.upsertLatestSnapshot(entity);
            ChatMemorySnapshotEntity persisted = selectSnapshot(context);
            if (persisted != null && persisted.getSnapshotVersion() != null
                    && persisted.getSnapshotVersion() >= dirtyVersion) {
                clearDirty(context);
            }
        });
    }

    @Override
    public void evict(String sessionId) {
        flushNow(sessionId);
        ChatMemoryContext context = resolveContext(sessionId);
        if (context == null) {
            return;
        }

        cancelScheduledFlush(sessionId);
        redissonUtil.executeWithLock(lockKey(sessionId), 3, TimeUnit.SECONDS, () -> {
            redisUtil.delete(memoryKey(context), versionKey(context), dirtyKey(context));
            for (ChatMemorySnapshotEntity snapshot : chatMemorySnapshotMapper.selectAllBySessionId(sessionId)) {
                ChatMemoryContext scopedContext = contextFromSnapshot(snapshot);
                redisUtil.delete(memoryKey(scopedContext), versionKey(scopedContext), dirtyKey(scopedContext));
            }
            String resident = redisUtil.get(residentKey(context.userId()), String.class);
            if (sessionId.equals(resident)) {
                redisUtil.delete(residentKey(context.userId()));
            }
        });
    }

    @Override
    public void markResident(String sessionId) {
        ChatMemoryContext target = resolveContext(sessionId);
        if (target == null) {
            return;
        }

        String currentResident = redisUtil.get(residentKey(target.userId()), String.class);
        if (StringUtils.isNotBlank(currentResident) && !sessionId.equals(currentResident)) {
            evict(currentResident);
        }

        redisUtil.set(residentKey(target.userId()), sessionId);
        restoreToRedis(sessionId);
    }

    @Override
    public void restoreToRedis(String sessionId) {
        ChatMemoryContext context = resolveContext(sessionId);
        if (context == null) {
            return;
        }

        redissonUtil.executeWithLock(lockKey(sessionId), 3, TimeUnit.SECONDS, () -> {
            List<ChatMemorySnapshotEntity> snapshots = chatMemorySnapshotMapper.selectAllBySessionId(sessionId);
            if (snapshots.isEmpty()) {
                ChatMemorySnapshotEntity snapshot = chatMemorySnapshotMapper.selectBySessionId(sessionId);
                if (snapshot != null) {
                    snapshots = List.of(snapshot);
                }
            }
            for (ChatMemorySnapshotEntity snapshot : snapshots) {
                if (snapshot == null || StringUtils.isBlank(snapshot.getMemoryPayloadJson())) {
                    continue;
                }
                ChatMemoryContext snapshotContext = contextFromSnapshot(snapshot);
                String payload = redisUtil.get(memoryKey(snapshotContext), String.class);
                if (StringUtils.isNotBlank(payload)) {
                    redisUtil.expire(memoryKey(snapshotContext), MEMORY_TTL_SECONDS);
                    redisUtil.expire(versionKey(snapshotContext), MEMORY_TTL_SECONDS);
                    Long dirtyVersion = readLong(dirtyKey(snapshotContext));
                    if (dirtyVersion != null) {
                        redisUtil.expire(dirtyKey(snapshotContext), MEMORY_TTL_SECONDS);
                    }
                    continue;
                }
                writeSnapshotToRedis(snapshotContext, snapshot.getMemoryPayloadJson(), snapshot.getSnapshotVersion());
                clearDirty(snapshotContext);
            }
        });
    }

    @Override
    public void deleteSnapshot(String sessionId) {
        cancelScheduledFlush(sessionId);
        chatMemorySnapshotMapper.deleteBySessionId(sessionId);
    }

    @Override
    public void deleteSnapshot(ChatMemoryContext context) {
        ScheduledFuture<?> future = pendingFlushes.remove(pendingKey(context));
        if (future != null) {
            future.cancel(false);
        }
        redisUtil.delete(memoryKey(context), versionKey(context), dirtyKey(context));
        chatMemorySnapshotMapper.deleteBySessionScope(context.sessionId(), context.agentId(), context.memoryScope());
    }

    @PreDestroy
    public void shutdownScheduler() {
        flushScheduler.shutdownNow();
    }

    private ChatMemoryContext resolveContext(String sessionId) {
        ChatSessionEntity session = chatSessionMapper.selectBySessionId(sessionId);
        if (session != null) {
            return new ChatMemoryContext(
                    session.getUserId(),
                    session.getPromptId(),
                    session.getSessionId(),
                    session.getAgentId(),
                    "default"
            );
        }

        ChatMemorySnapshotEntity snapshot = chatMemorySnapshotMapper.selectBySessionId(sessionId);
        if (snapshot == null) {
            return null;
        }
        return contextFromSnapshot(snapshot);
    }

    private long nextSnapshotVersion(ChatMemoryContext context) {
        Long current = readLong(versionKey(context));
        if (current == null) {
            ChatMemorySnapshotEntity snapshot = selectSnapshot(context);
            current = snapshot == null || snapshot.getSnapshotVersion() == null ? 0L : snapshot.getSnapshotVersion();
            redisUtil.set(versionKey(context), current, MEMORY_TTL_SECONDS);
        }
        return redisUtil.increment(versionKey(context));
    }

    private Integer countMessages(String payload) {
        return ChatMessageDeserializer.messagesFromJson(payload).size();
    }

    private Integer resolveSourceMessageCount(ChatSessionEntity session, Integer windowSize) {
        if (session == null || session.getMessageCount() == null) {
            return windowSize == null ? 0 : windowSize;
        }
        return Math.max(session.getMessageCount(), windowSize == null ? 0 : windowSize);
    }

    private void writeSnapshotToRedis(ChatMemoryContext context, String payload, Long version) {
        redisUtil.set(memoryKey(context), payload, MEMORY_TTL_SECONDS);
        long seedVersion = version == null ? 0L : version;
        redisUtil.set(versionKey(context), seedVersion, MEMORY_TTL_SECONDS);
    }

    private void markDirty(ChatMemoryContext context, long version) {
        redisUtil.set(dirtyKey(context), version, MEMORY_TTL_SECONDS);
    }

    private void clearDirty(ChatMemoryContext context) {
        redisUtil.delete(dirtyKey(context));
    }

    private Long readLong(String key) {
        Object value = redisUtil.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        if (value instanceof String text && StringUtils.isNotBlank(text)) {
            return Long.parseLong(text);
        }
        return null;
    }

    private void cancelScheduledFlush(String sessionId) {
        for (String pendingKey : List.copyOf(pendingFlushes.keySet())) {
            if (pendingKey.startsWith(sessionId + ":")) {
                ScheduledFuture<?> future = pendingFlushes.remove(pendingKey);
                if (future != null) {
                    future.cancel(false);
                }
            }
        }
    }

    private String memoryKey(ChatMemoryContext context) {
        return "chat:memory:" + context.userId()
                + ":" + context.sessionId()
                + ":" + context.agentId()
                + ":" + context.memoryScope();
    }

    private String versionKey(ChatMemoryContext context) {
        return "chat:memory:version:" + context.userId()
                + ":" + context.sessionId()
                + ":" + context.agentId()
                + ":" + context.memoryScope();
    }

    private String dirtyKey(ChatMemoryContext context) {
        return "chat:memory:dirty:" + context.userId()
                + ":" + context.sessionId()
                + ":" + context.agentId()
                + ":" + context.memoryScope();
    }

    private String residentKey(Long userId) {
        return "chat:memory:resident:" + userId;
    }

    private String lockKey(String sessionId) {
        return "chat:memory:lock:" + sessionId;
    }

    private String lockKey(ChatMemoryContext context) {
        return "chat:memory:lock:" + context.sessionId() + ":" + context.agentId() + ":" + context.memoryScope();
    }

    private String pendingKey(ChatMemoryContext context) {
        return context.sessionId() + ":" + context.agentId() + ":" + context.memoryScope();
    }

    private ChatMemorySnapshotEntity selectSnapshot(ChatMemoryContext context) {
        return chatMemorySnapshotMapper.selectBySessionScope(
                context.sessionId(),
                context.agentId(),
                context.memoryScope()
        );
    }

    private ChatMemoryContext contextFromSnapshot(ChatMemorySnapshotEntity snapshot) {
        return new ChatMemoryContext(
                snapshot.getUserId(),
                snapshot.getPromptId(),
                snapshot.getSessionId(),
                StringUtils.isBlank(snapshot.getAgentId()) ? "standard-chat" : snapshot.getAgentId(),
                StringUtils.isBlank(snapshot.getMemoryScope()) ? "default" : snapshot.getMemoryScope()
        );
    }

    private static final class SnapshotThreadFactory implements ThreadFactory {

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "chat-memory-snapshot-flusher");
            thread.setDaemon(true);
            return thread;
        }
    }
}
