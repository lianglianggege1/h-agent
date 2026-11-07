package com.h.backend.document.infrastructure.persistence;

import static org.junit.jupiter.api.Assertions.*;

import com.h.backend.document.domain.DocumentTask;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

@EnabledIfEnvironmentVariable(named = "DOCUMENT_TEST_DATABASE_URL", matches = ".+")
class DocumentRepositoryTest {
    JdbcTemplate db;
    DocumentRepository repository;

    @BeforeEach
    void setup() throws Exception {
        var dataSource =
                new DriverManagerDataSource(
                        System.getenv("DOCUMENT_TEST_DATABASE_URL"), "postgres", "");
        db = new JdbcTemplate(dataSource);
        db.execute(
                "DROP TABLE IF EXISTS"
                    + " chat_document_tasks,chat_document_settings,chat_message_resources CASCADE");
        db.execute("CREATE TABLE chat_message_resources(id TEXT,file_name TEXT)");
        db.execute("INSERT INTO chat_message_resources VALUES ('legacy','旧报告.DOCX')");
        for (String sql :
                Files.readString(
                                Path.of(
                                        "src/main/resources/db/migration/V20261105_01__document_workflows.sql"))
                        .split(";")) if (!sql.isBlank()) db.execute(sql);
        db.execute(
                Files.readString(
                        Path.of(
                                "src/main/resources/db/migration/V20261106_01__document_schema_comments.sql")));
        var factory = new com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        var configuration = new com.baomidou.mybatisplus.core.MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(
                com.h.backend.document.infrastructure.persistence.mapper.DocumentTaskMapper.class);
        factory.setConfiguration(configuration);
        factory.setMapperLocations(
                new org.springframework.core.io.support.PathMatchingResourcePatternResolver()
                        .getResources("classpath*:mapper/document/*.xml"));
        var session = new org.mybatis.spring.SqlSessionTemplate(factory.getObject());
        repository =
                new DocumentRepository(
                        session.getMapper(
                                com.h.backend.document.infrastructure.persistence.mapper
                                        .DocumentTaskMapper.class),
                        new ObjectMapper(),
                        new DataSourceTransactionManager(dataSource));
    }

    DocumentTask task() {
        var t = new DocumentTask();
        t.setId(UUID.randomUUID().toString());
        t.setUserId(1L);
        t.setSessionId("chat");
        t.setRequestKey("same");
        t.setStatus("QUEUED");
        return t;
    }

    @Test
    void migrationPreservesOriginalExtension() {
        assertEquals(
                "docx",
                db.queryForObject(
                        "SELECT file_extension FROM chat_message_resources WHERE id='legacy'",
                        String.class));
    }

    @Test
    void automaticallyRecoversLegacyAndUnavailableTasksButPreservesCancellation() {
        var legacy = task();
        legacy.setStatus("NEEDS_EXTERNAL_CONSENT");
        var stored = repository.insert(legacy);
        assertEquals(1, repository.requeueUnavailable());
        assertEquals("QUEUED", repository.find(1L, "chat", stored.getId()).getStatus());
        repository.change(
                1L,
                "chat",
                stored.getId(),
                current -> {
                    current.setStatus("CLOUD_UNAVAILABLE");
                    current.setContinuationCancelled(true);
                });
        assertEquals(0, repository.requeueUnavailable());
        assertEquals("CLOUD_UNAVAILABLE", repository.find(1L, "chat", stored.getId()).getStatus());
    }

    @Test
    void legacyCoverageMigrationRechecksWithoutDiscardingHumanDecisions() throws Exception {
        var legacy = task();
        legacy.setStatus("VERIFICATION_FAILED");
        var page = new DocumentTask.Page();
        page.setQuality("LOW");
        page.setJobId("existing-job");
        var block = new DocumentTask.Block();
        block.setVerdict("COVERAGE_ERROR");
        page.getBlocks().add(block);
        legacy.getPages().add(page);
        var stored = repository.insert(legacy);
        var decided = task();
        decided.setRequestKey("decided");
        decided.setStatus("VERIFICATION_FAILED");
        var protectedPage = new DocumentTask.Page();
        var protectedBlock = new DocumentTask.Block();
        protectedBlock.setVerdict("COVERAGE_ERROR");
        protectedBlock.setDecision("CORRECT");
        protectedPage.getBlocks().add(protectedBlock);
        decided.getPages().add(protectedPage);
        var protectedTask = repository.insert(decided);
        db.execute(
                Files.readString(
                        Path.of(
                                "src/main/resources/db/migration/V20261106_03__recheck_ocr_coverage.sql")));
        var repaired = repository.find(1L, "chat", stored.getId());
        assertEquals("QUEUED", repaired.getStatus());
        assertTrue(repaired.getPages().getFirst().getBlocks().isEmpty());
        assertEquals("existing-job", repaired.getPages().getFirst().getJobId());
        assertEquals(
                "VERIFICATION_FAILED",
                repository.find(1L, "chat", protectedTask.getId()).getStatus());
    }

    @Test
    void deferredContinuationDoesNotStarveLaterTasks() {
        var first = task();
        first.setStatus("RECOGNIZED");
        first.setResumeStatus("QUEUED");
        repository.insert(first);
        var second = task();
        second.setRequestKey("later");
        second.setStatus("RECOGNIZED");
        second.setResumeStatus("QUEUED");
        repository.insert(second);

        assertEquals(first.getId(), repository.claim().getFirst().getId());
        repository.release(first.getId());
        assertEquals(second.getId(), repository.claim().getFirst().getId(),
                "a deferred or busy conversation must not block every later continuation");
    }

    @Test
    void concurrentInsertAndClaimOnlyCreateAndLeaseOnce() throws Exception {
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = new ArrayList<Future<DocumentTask>>();
            for (int i = 0; i < 8; i++)
                futures.add(executor.submit(() -> repository.insert(task())));
            var ids = new HashSet<String>();
            for (var future : futures) ids.add(future.get().getId());
            assertEquals(1, ids.size());
            var claims = new ArrayList<Future<List<DocumentTask>>>();
            for (int i = 0; i < 8; i++) claims.add(executor.submit(repository::claim));
            int leased = 0;
            for (var claim : claims) leased += claim.get().size();
            assertEquals(1, leased);
            repository.release(ids.iterator().next());
            assertEquals(1, repository.claim().size());
        }
    }

    @Test
    void staleWorkersCannotOverwriteCancellationAndMutationsRollback() {
        var stored = repository.insert(task());
        var stale = repository.find(1L, "chat", stored.getId());
        repository.change(1L, "chat", stored.getId(), t -> t.setStatus("CANCELLED"));
        stale.setStatus("VERIFIED");
        assertFalse(repository.checkpoint(stale));
        assertEquals("CANCELLED", repository.find(1L, "chat", stored.getId()).getStatus());
        assertThrows(
                IllegalStateException.class,
                () ->
                        repository.change(
                                1L,
                                "chat",
                                stored.getId(),
                                t -> {
                                    t.setStatus("VERIFIED");
                                    throw new IllegalStateException();
                                }));
        assertEquals("CANCELLED", repository.find(1L, "chat", stored.getId()).getStatus());
        assertThrows(RuntimeException.class, () -> repository.find(2L, "chat", stored.getId()));
    }
}
