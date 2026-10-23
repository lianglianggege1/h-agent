package com.h.backend.chat.application;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.h.backend.chat.application.impl.SystemPromptServiceImpl;
import com.h.backend.chat.infrastructure.persistence.mapper.SystemPromptMapper;
import com.h.backend.chat.interfaces.dto.CreateSystemPromptRequest;
import com.h.backend.chat.interfaces.dto.SystemPromptResponse;
import com.h.backend.chat.interfaces.dto.UpdateSystemPromptRequest;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.dao.DataIntegrityViolationException;
import com.h.backend.common.exception.BusinessException;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class SystemPromptServicePersistenceTest {
    static JdbcTemplate admin;
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static SystemPromptService service;
    static String schema;

    @BeforeAll
    static void setup() throws Exception {
        Properties config = new Properties();
        Path env = Files.exists(Path.of(".env")) ? Path.of(".env") : Path.of("../.env");
        if (Files.exists(env)) try (var reader = Files.newBufferedReader(env)) { config.load(reader); }
        String url = System.getenv().getOrDefault("DB_URL", config.getProperty("DB_URL"));
        assertNotNull(url, "DB_URL is required for PostgreSQL persistence tests");
        String user = System.getenv().getOrDefault("DB_USERNAME", config.getProperty("DB_USERNAME"));
        String password = System.getenv().getOrDefault("DB_PASSWORD", config.getProperty("DB_PASSWORD"));
        var adminDs = new DriverManagerDataSource(url, user, password);
        admin = new JdbcTemplate(adminDs);
        schema = "prompt_test_" + UUID.randomUUID().toString().replace("-", "");
        admin.execute("CREATE SCHEMA " + schema);
        String testUrl = url.replaceAll("([?&])currentSchema=[^&]*", "$1currentSchema=" + schema);
        if (!testUrl.contains("currentSchema=")) testUrl += (testUrl.contains("?") ? "&" : "?") + "currentSchema=" + schema;
        var ds = new DriverManagerDataSource(testUrl, user, password);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE users (id BIGINT PRIMARY KEY)");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V2__create_system_prompts.sql")));
        jdbc.execute("CREATE TABLE chat_sessions (id BIGSERIAL PRIMARY KEY, prompt_id BIGINT REFERENCES system_prompts(id))");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(SystemPromptMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(ds);
        factory.setConfiguration(configuration);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        service = new SystemPromptServiceImpl(new SqlSessionTemplate(factory.getObject()).getMapper(SystemPromptMapper.class));
        tx = new TransactionTemplate(new DataSourceTransactionManager(ds));
        Path migration = Path.of("src/main/resources/db/migration/V20261023_02__system_prompt_lifecycle.sql");
        if (Files.exists(migration)) jdbc.execute(Files.readString(migration));
    }

    @BeforeEach
    void reset() {
        jdbc.execute("TRUNCATE chat_sessions, system_prompts, users RESTART IDENTITY CASCADE");
        jdbc.execute("INSERT INTO users VALUES (1), (2)");
    }

    @AfterAll
    static void cleanup() { if (admin != null && schema != null) admin.execute("DROP SCHEMA " + schema + " CASCADE"); }

    List<SystemPromptResponse> list() { return tx.execute(s -> service.listPrompts(1L)); }

    @Test
    void deletingReferencedPromptPreservesHistoryAndDoesNotReappear() {
        var prompt = list().getFirst();
        jdbc.update("INSERT INTO chat_sessions(prompt_id) VALUES (?)", prompt.id());
        assertDoesNotThrow(() -> tx.executeWithoutResult(s -> service.deletePrompt(1L, prompt.id())));
        assertTrue(list().isEmpty(), "Deleting the last prompt must not recreate it on refresh");
        assertEquals(prompt.id(), jdbc.queryForObject("SELECT prompt_id FROM chat_sessions", Long.class));
        assertEquals(prompt.content(), service.getSystemPrompt(1L, prompt.id()));
        assertEquals(prompt.id(), service.resolvePromptId(1L, prompt.id()));
        assertThrows(BusinessException.class, () -> tx.execute(s -> service.setDefaultPrompt(1L, prompt.id())));
        assertThrows(BusinessException.class, () -> tx.execute(s -> service.updatePrompt(1L, prompt.id(), new UpdateSystemPromptRequest("renamed", "changed"))));
        assertThrows(BusinessException.class, () -> service.getSystemPrompt(2L, prompt.id()));
    }

    @Test
    void deletingDefaultSelectsRemainingPromptAndCreatingAfterDeletionHasOneDefault() {
        var first = list().getFirst();
        var second = tx.execute(s -> service.createPrompt(1L, new CreateSystemPromptRequest("Custom", "Custom content")));
        tx.executeWithoutResult(s -> service.deletePrompt(1L, first.id()));
        assertEquals(List.of(second.id()), list().stream().map(SystemPromptResponse::id).toList());
        assertTrue(list().getFirst().isDefault());
        tx.executeWithoutResult(s -> service.deletePrompt(1L, second.id()));
        var third = tx.execute(s -> service.createPrompt(1L, new CreateSystemPromptRequest("New", "New content")));
        assertEquals(1, list().size());
        assertEquals(third.id(), list().getFirst().id());
        assertTrue(list().getFirst().isDefault());
    }

    @Test
    void concurrentInitializationCreatesOnlyOnePrompt() throws Exception {
        try (var pool = Executors.newFixedThreadPool(12)) {
            var start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 12; i++) futures.add(pool.submit(() -> {
                start.await();
                tx.execute(s -> service.listPrompts(1L));
                return null;
            }));
            start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        }
        assertEquals(1, list().size(), "Concurrent first visits must not create duplicate defaults");
    }

    @Test
    void switchingDefaultsKeepsExactlyOneDefault() {
        var first = list().getFirst();
        var second = tx.execute(s -> service.createPrompt(1L, new CreateSystemPromptRequest("Custom", "Content")));
        tx.execute(s -> service.setDefaultPrompt(1L, second.id()));
        tx.execute(s -> service.setDefaultPrompt(1L, first.id()));
        assertEquals(1, list().stream().filter(SystemPromptResponse::isDefault).count());
        assertEquals(first.id(), list().getFirst().id());
    }

    @Test
    void migrationRepairsDuplicateDefaultsWithoutBreakingHistory() throws Exception {
        jdbc.execute("ALTER TABLE system_prompts DROP COLUMN deleted_at CASCADE");
        jdbc.execute("INSERT INTO system_prompts(user_id,name,content,is_default) VALUES "
                + "(1,'Default','Same content',TRUE),(1,'Default','Same content',TRUE),"
                + "(1,'Custom','Different content',TRUE)");
        jdbc.execute("INSERT INTO chat_sessions(prompt_id) VALUES (2)");
        jdbc.execute(Files.readString(Path.of("src/main/resources/db/migration/V20261023_02__system_prompt_lifecycle.sql")));
        assertEquals(List.of(1L, 3L), list().stream().map(SystemPromptResponse::id).toList());
        assertEquals(1, list().stream().filter(SystemPromptResponse::isDefault).count());
        assertEquals("Same content", service.getSystemPrompt(1L, 2L));
        assertEquals(2L, jdbc.queryForObject("SELECT prompt_id FROM chat_sessions", Long.class));
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.execute(
                "INSERT INTO system_prompts(user_id,name,content,is_default) VALUES (1,'Other','Other',TRUE)"));
    }
}
