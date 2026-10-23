package com.h.backend.chat.agui;

import com.h.backend.chat.infrastructure.persistence.mapper.AguiRunSnapshotMapper;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/** Uses connection-local temporary tables and rolls back; never migrates the application's database. */
@EnabledIfEnvironmentVariable(named = "AGUI_TEST_DB_URL", matches = ".+")
class AguiRunSnapshotPersistenceTest {
    @Test
    void migrationAndMapperRoundTripOnPostgres() throws Exception {
        try (var connection = DriverManager.getConnection(System.getenv("AGUI_TEST_DB_URL"),
                System.getenv("AGUI_TEST_DB_USERNAME"), System.getenv("AGUI_TEST_DB_PASSWORD"))) {
            connection.setAutoCommit(false);
            try {
                connection.createStatement().execute("CREATE TEMP TABLE agent_sessions(session_id VARCHAR(128) PRIMARY KEY)");
                String migration;
                try (var input = getClass().getResourceAsStream("/db/migration/V20261023_01__agui_run_snapshots.sql")) {
                    migration = new String(Objects.requireNonNull(input).readAllBytes(), StandardCharsets.UTF_8);
                }
                connection.createStatement().execute(migration.replace("CREATE TABLE chat_interaction_runs", "CREATE TEMP TABLE chat_interaction_runs"));
                connection.createStatement().execute("INSERT INTO agent_sessions VALUES ('thread')");
                var configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(),
                        new SingleConnectionDataSource(connection, true)));
                configuration.setMapUnderscoreToCamelCase(true);
                configuration.addMapper(AguiRunSnapshotMapper.class);
                try (var sql = new SqlSessionFactoryBuilder().build(configuration).openSession()) {
                    var mapper = sql.getMapper(AguiRunSnapshotMapper.class);
                    mapper.insert(new AguiRunSnapshotMapper.Snapshot("run", "thread", 1L, "hash", "RUNNING", "{}", null));
                    assertEquals("thread", mapper.find(1L, "run").sessionId());
                    assertNotNull(mapper.list(1L, "thread").getFirst().createdAt());
                    assertNull(mapper.find(2L, "run"));
                    assertEquals(1, mapper.requestStop(1L, "thread", null));
                    assertEquals("CANCEL_REQUESTED", mapper.find(1L, "run").status());
                    assertEquals(0, mapper.interruptStale(1L, "run"));
                    connection.createStatement().execute("UPDATE chat_interaction_runs SET updated_at = NOW() - INTERVAL '2 minutes'");
                    assertEquals(1, mapper.interruptStale(1L, "run"));
                    mapper.update("run", 1L, "CANCELLED", "{\"activities\":[]}");
                    assertEquals("CANCELLED", mapper.find(1L, "run").status());
                    mapper.insert(new AguiRunSnapshotMapper.Snapshot("run-2", "thread", 1L, "hash-2", "RUNNING", "{}", null));
                    assertEquals(0, mapper.requestStop(1L, "thread", "run"));
                    assertEquals(0, mapper.requestStop(2L, "thread", "run-2"));
                    var savepoint = connection.setSavepoint();
                    assertThrows(org.apache.ibatis.exceptions.PersistenceException.class, () -> mapper.insert(
                            new AguiRunSnapshotMapper.Snapshot("run-3", "thread", 1L, "hash-3", "RUNNING", "{}", null)));
                    connection.rollback(savepoint);
                    connection.createStatement().execute("DELETE FROM agent_sessions WHERE session_id = 'thread'");
                    sql.clearCache();
                    assertTrue(mapper.list(1L, "thread").isEmpty());
                }
            } finally { if (!connection.getAutoCommit()) connection.rollback(); }
        }
    }
}
