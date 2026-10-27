package com.h.backend.chat.infrastructure.persistence;

import com.h.backend.chat.infrastructure.persistence.mapper.ImageGenerationTaskMapper;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ImageGenerationQueuePersistenceTest {
    @Test
    void concurrentWorkersClaimDifferentPersistedTasksAndNeverResubmitRunningWork() throws Exception {
        String url = System.getProperty("image.test.jdbc-url");
        assumeTrue(url != null, "Run against an isolated PostgreSQL database with -Dimage.test.jdbc-url");
        var source = new UnpooledDataSource("org.postgresql.Driver", url, "postgres", "");
        String schema = "image_queue_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = source.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            try {
                statement.execute("SET search_path TO " + schema);
                statement.execute("CREATE TABLE chat_session_messages(id BIGINT PRIMARY KEY)");
                statement.execute("INSERT INTO chat_session_messages VALUES (501), (502)");
                try (var migration = getClass().getResourceAsStream("/db/migration/V20261027_01__image_generation_tasks.sql")) {
                    assertNotNull(migration);
                    statement.execute(new String(migration.readAllBytes(), StandardCharsets.UTF_8));
                }
                var scoped = new UnpooledDataSource("org.postgresql.Driver", url + "?currentSchema=" + schema, "postgres", "");
                var configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), scoped));
                configuration.addMapper(ImageGenerationTaskMapper.class);
                var factory = new SqlSessionFactoryBuilder().build(configuration);
                try (var setup = factory.openSession(true)) {
                    var tasks = setup.getMapper(ImageGenerationTaskMapper.class);
                    tasks.enqueue(501L, "{\"prompt\":\"text\"}");
                    tasks.enqueue(502L, "{\"prompt\":\"reference\"}");
                }
                try (var first = factory.openSession(false); var second = factory.openSession(false)) {
                    var firstTasks = first.getMapper(ImageGenerationTaskMapper.class);
                    var secondTasks = second.getMapper(ImageGenerationTaskMapper.class);
                    var a = firstTasks.claim();
                    var b = secondTasks.claim();
                    assertEquals(501L, a.messageId());
                    assertEquals(502L, b.messageId());
                    assertTrue(b.commandJson().contains("reference"));
                    first.commit();
                    second.commit();
                    assertNull(firstTasks.claim());
                    firstTasks.finish(501L, "SUCCEEDED");
                    first.commit();
                }
                statement.execute("UPDATE image_generation_tasks SET started_at = CURRENT_TIMESTAMP - INTERVAL '2 hours' WHERE message_id = 502");
                try (var recovery = factory.openSession(true)) {
                    var tasks = recovery.getMapper(ImageGenerationTaskMapper.class);
                    assertEquals(502L, tasks.findStale(3600).getFirst().messageId());
                    tasks.finish(502L, "SUBMISSION_UNKNOWN");
                    assertNull(tasks.claim());
                    assertTrue(tasks.findStale(3600).isEmpty());
                }
            } finally {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }
}
