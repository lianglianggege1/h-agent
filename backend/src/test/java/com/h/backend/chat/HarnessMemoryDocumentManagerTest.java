package com.h.backend.chat;

import com.h.backend.chat.application.HarnessMemoryDocumentManager;
import com.h.backend.chat.domain.memory.HarnessMemoryDocument;
import com.h.backend.chat.domain.memory.HarnessMemoryDocumentException;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.remote.store.InMemoryStore;
import io.agentscope.harness.agent.filesystem.remote.store.StoreItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HarnessMemoryDocumentManagerTest {

    private static final long USER_ID = 1L;

    /**
     * 与 AgentScope RemoteFilesystemSpec（USER 隔离 + root 路由）约定的存储位置一致，
     * 用于在 BaseStore seam 上布置初始状态。
     */
    private static final List<String> NAMESPACE =
            List.of("agents", "harness-agent", "users", String.valueOf(USER_ID), "root");
    private static final String ITEM_KEY = "/MEMORY.md";

    private static final String DEFAULT_TEMPLATE = """
            # 用户长期记忆

            ## 工作偏好

            ## 个人信息

            ## 项目知识

            ## 表达方式
            """;

    private final InMemoryStore store = new InMemoryStore();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-08-31T06:00:00Z"));
    private final HarnessMemoryDocumentManager manager = new HarnessMemoryDocumentManager(store, clock);

    @Test
    void viewReturnsDefaultTemplateWithoutPersistingAnything() {
        HarnessMemoryDocument document = manager.view(USER_ID);

        assertEquals(DEFAULT_TEMPLATE, document.content());
        assertEquals(0L, document.revision());
        assertFalse(document.exists());
        assertNull(document.updatedAt());
        assertEquals(0, store.size());
    }

    @Test
    void viewReturnsStoredDocumentWithRevisionAndUpdatedAt() {
        seedStoredFile("已有记忆", "2026-08-31T06:30:00Z");

        HarnessMemoryDocument document = manager.view(USER_ID);

        assertTrue(document.exists());
        assertEquals("已有记忆", document.content());
        assertEquals(1L, document.revision());
        assertEquals(Instant.parse("2026-08-31T06:30:00Z"), document.updatedAt());
    }

    @Test
    void viewTreatsMissingModifiedAtAsUnknownUpdateTime() {
        seedStoredFile("已有记忆", null);

        assertNull(manager.view(USER_ID).updatedAt());
    }

    @Test
    void viewAcceptsMissingEncoding() {
        seedRaw(Map.of("content", "文本", "modified_at", "2026-08-31T06:30:00Z"));

        HarnessMemoryDocument document = manager.view(USER_ID);

        assertEquals("文本", document.content());
        assertEquals(Instant.parse("2026-08-31T06:30:00Z"), document.updatedAt());
    }

    @Test
    void viewRejectsNonStringContentAsCorrupt() {
        seedRaw(Map.of("content", 42, "encoding", "utf-8"));

        assertKind(HarnessMemoryDocumentException.Kind.CONTENT_CORRUPT, () -> manager.view(USER_ID));
    }

    @Test
    void viewRejectsUnexpectedEncodingAsCorrupt() {
        seedRaw(Map.of("content", "文本", "encoding", "base64"));

        assertKind(HarnessMemoryDocumentException.Kind.CONTENT_CORRUPT, () -> manager.view(USER_ID));
    }

    @Test
    void viewRejectsMalformedModifiedAtAsCorrupt() {
        seedRaw(Map.of("content", "文本", "encoding", "utf-8", "modified_at", "yesterday"));

        assertKind(HarnessMemoryDocumentException.Kind.CONTENT_CORRUPT, () -> manager.view(USER_ID));
    }

    @Test
    void viewReportsStoreFailureAsUnavailableWithoutLeakingCause() {
        InstrumentedStore broken = new InstrumentedStore();
        broken.failOnGet();
        HarnessMemoryDocumentManager brokenManager = new HarnessMemoryDocumentManager(broken, clock);

        HarnessMemoryDocumentException exception = assertKind(
                HarnessMemoryDocumentException.Kind.STORE_UNAVAILABLE, () -> brokenManager.view(USER_ID));

        assertFalse(exception.getMessage().contains("connection refused"));
    }

    @Test
    void saveCreatesDocumentWhenFileDoesNotExist() {
        HarnessMemoryDocument saved = manager.save(USER_ID, "我的记忆", 0L);

        assertTrue(saved.exists());
        assertEquals("我的记忆", saved.content());
        assertEquals(1L, saved.revision());
        assertEquals(Instant.parse("2026-08-31T06:00:00Z"), saved.updatedAt());

        StoreItem item = store.get(NAMESPACE, ITEM_KEY);
        assertEquals(1L, item.version());
        Map<String, Object> value = item.value();
        assertEquals("我的记忆", value.get("content"));
        assertEquals("utf-8", value.get("encoding"));
        assertEquals("2026-08-31T06:00:00Z", value.get("created_at"));
        assertEquals("2026-08-31T06:00:00Z", value.get("modified_at"));
        assertEquals(4, value.size());
    }

    @Test
    void saveRejectsCreationWithNonZeroExpectedRevision() {
        assertKind(HarnessMemoryDocumentException.Kind.REVISION_CONFLICT,
                () -> manager.save(USER_ID, "内容", 1L));

        assertEquals(0, store.size());
    }

    @Test
    void saveUpdatesContentWhilePreservingCreatedAtAndUnknownFields() {
        seedStoredFile("第一版", "2026-08-31T05:30:00Z");
        Map<String, Object> withSdkFutureField = new LinkedHashMap<>(store.get(NAMESPACE, ITEM_KEY).value());
        withSdkFutureField.put("sdk_future_field", "keep-me");
        store.put(NAMESPACE, ITEM_KEY, withSdkFutureField);

        clock.advance(Duration.ofMinutes(30));
        HarnessMemoryDocument saved = manager.save(USER_ID, "第二版", 2L);

        assertEquals(3L, saved.revision());
        assertEquals("第二版", saved.content());
        assertEquals(Instant.parse("2026-08-31T06:30:00Z"), saved.updatedAt());

        Map<String, Object> value = store.get(NAMESPACE, ITEM_KEY).value();
        assertEquals("第二版", value.get("content"));
        assertEquals("utf-8", value.get("encoding"));
        assertEquals("2026-08-31T05:00:00Z", value.get("created_at"));
        assertEquals("2026-08-31T06:30:00Z", value.get("modified_at"));
        assertEquals("keep-me", value.get("sdk_future_field"));
    }

    @Test
    void saveRejectsStaleExpectedRevisionWithoutOverwriting() {
        seedStoredFile("服务端内容", "2026-08-31T06:30:00Z");

        assertKind(HarnessMemoryDocumentException.Kind.REVISION_CONFLICT,
                () -> manager.save(USER_ID, "旧基线内容", 0L));

        assertEquals("服务端内容", manager.view(USER_ID).content());
    }

    @Test
    void saveRejectsFutureExpectedRevision() {
        seedStoredFile("服务端内容", "2026-08-31T06:30:00Z");

        assertKind(HarnessMemoryDocumentException.Kind.REVISION_CONFLICT,
                () -> manager.save(USER_ID, "内容", 5L));
    }

    @Test
    void saveDoesNotOverwriteWhenConcurrentWriterWinsCas() {
        InstrumentedStore racing = new InstrumentedStore();
        HarnessMemoryDocumentManager racingManager = new HarnessMemoryDocumentManager(racing, clock);
        racing.put(NAMESPACE, ITEM_KEY, Map.of(
                "content", "服务端原始内容",
                "encoding", "utf-8",
                "modified_at", "2026-08-31T05:00:00Z"));
        racing.raceBeforeNextPutIfVersion();

        assertKind(HarnessMemoryDocumentException.Kind.REVISION_CONFLICT,
                () -> racingManager.save(USER_ID, "用户的旧基线内容", 1L));

        assertEquals("Agent 并发写入", racing.storedContent());
    }

    @Test
    void saveReportsStoreFailureAsUnavailable() {
        InstrumentedStore broken = new InstrumentedStore();
        broken.failOnPutIfVersion();
        HarnessMemoryDocumentManager brokenManager = new HarnessMemoryDocumentManager(broken, clock);

        assertKind(HarnessMemoryDocumentException.Kind.STORE_UNAVAILABLE,
                () -> brokenManager.save(USER_ID, "内容", 0L));
    }

    @Test
    void saveAcceptsContentAtExactByteLimit() {
        String content = "a".repeat(65_536);

        HarnessMemoryDocument saved = manager.save(USER_ID, content, 0L);

        assertEquals(1L, saved.revision());
        assertEquals(content, manager.view(USER_ID).content());
    }

    @Test
    void saveMeasuresByteLimitInUtf8BytesNotCharacters() {
        // 21,846 个汉字 = 65,538 字节：字符数远低于上限，但字节超限。
        assertKind(HarnessMemoryDocumentException.Kind.CONTENT_TOO_LARGE,
                () -> manager.save(USER_ID, "中".repeat(21_846), 0L));

        assertEquals(0, store.size());
    }

    @Test
    void saveAcceptsEmojiAtExactByteLimit() {
        String content = "😀".repeat(16_384);

        manager.save(USER_ID, content, 0L);

        assertEquals(content, manager.view(USER_ID).content());
    }

    @Test
    void saveRejectsContentAboveByteLimit() {
        assertKind(HarnessMemoryDocumentException.Kind.CONTENT_TOO_LARGE,
                () -> manager.save(USER_ID, "a".repeat(65_537), 0L));

        assertEquals(0, store.size());
    }

    @Test
    void saveAllowsEmptyMarkdown() {
        HarnessMemoryDocument saved = manager.save(USER_ID, "", 0L);

        assertEquals("", saved.content());
        assertTrue(saved.exists());
        assertEquals("", manager.view(USER_ID).content());
    }

    @Test
    void usersAreIsolatedInSeparateNamespaces() {
        manager.save(USER_ID, "用户一的记忆", 0L);

        HarnessMemoryDocument otherView = manager.view(2L);
        assertFalse(otherView.exists());
        assertEquals(DEFAULT_TEMPLATE, otherView.content());

        manager.save(2L, "用户二的记忆", 0L);

        assertEquals("用户一的记忆", manager.view(USER_ID).content());
        assertEquals("用户二的记忆", manager.view(2L).content());
        assertEquals(2, store.size());
    }

    private void seedStoredFile(String content, String modifiedAt) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("content", content);
        value.put("encoding", "utf-8");
        value.put("created_at", "2026-08-31T05:00:00Z");
        if (modifiedAt != null) {
            value.put("modified_at", modifiedAt);
        }
        store.put(NAMESPACE, ITEM_KEY, value);
    }

    private void seedRaw(Map<String, Object> value) {
        store.put(NAMESPACE, ITEM_KEY, value);
    }

    private static HarnessMemoryDocumentException assertKind(
            HarnessMemoryDocumentException.Kind kind, Executable executable) {
        HarnessMemoryDocumentException exception =
                assertThrows(HarnessMemoryDocumentException.class, executable);
        assertEquals(kind, exception.kind());
        return exception;
    }

    /**
     * 在 BaseStore seam 上模拟并发写入者与存储故障。
     */
    private static final class InstrumentedStore implements BaseStore {

        private final InMemoryStore delegate = new InMemoryStore();
        private boolean failGet;
        private boolean failPutIfVersion;
        private boolean raceBeforeNextPutIfVersion;

        void failOnGet() {
            this.failGet = true;
        }

        void failOnPutIfVersion() {
            this.failPutIfVersion = true;
        }

        void raceBeforeNextPutIfVersion() {
            this.raceBeforeNextPutIfVersion = true;
        }

        String storedContent() {
            StoreItem item = delegate.get(NAMESPACE, ITEM_KEY);
            return item == null ? null : String.valueOf(item.value().get("content"));
        }

        @Override
        public StoreItem get(List<String> namespace, String key) {
            if (failGet) {
                throw new IllegalStateException("connection refused");
            }
            return delegate.get(namespace, key);
        }

        @Override
        public void put(List<String> namespace, String key, Map<String, Object> value) {
            delegate.put(namespace, key, value);
        }

        @Override
        public boolean putIfVersion(
                List<String> namespace, String key, Map<String, Object> value, long expectedVersion) {
            if (raceBeforeNextPutIfVersion) {
                raceBeforeNextPutIfVersion = false;
                // 模拟并发写入者在 manager 读取之后、CAS 之前落地 N+1 版本。
                delegate.put(namespace, key, Map.of(
                        "content", "Agent 并发写入",
                        "encoding", "utf-8",
                        "modified_at", "2026-08-31T05:30:00Z"));
            }
            if (failPutIfVersion) {
                throw new IllegalStateException("connection refused");
            }
            return delegate.putIfVersion(namespace, key, value, expectedVersion);
        }

        @Override
        public List<StoreItem> search(List<String> namespace, int limit, int offset) {
            return delegate.search(namespace, limit, offset);
        }

        @Override
        public void delete(List<String> namespace, String key) {
            delegate.delete(namespace, key);
        }
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
