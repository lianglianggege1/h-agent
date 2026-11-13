package com.h.backend.skill.infrastructure.persistence;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.h.backend.skill.application.SkillCatalogService;
import com.h.backend.skill.domain.*;
import com.h.backend.skill.domain.source.SkillSourceRepository;
import com.h.backend.skill.domain.source.SkillSourceRepository.SourceSnapshot;
import com.h.backend.skill.domain.tar.DeterministicSkillTarBuilder;
import com.h.backend.skill.infrastructure.artifact.SkillArtifactPublisher;
import com.h.backend.skill.infrastructure.artifact.SkillArtifactResolver;
import com.h.backend.skill.infrastructure.artifact.VerifiedSkillBundle;
import com.h.backend.skill.infrastructure.config.SkillPlatformProperties;
import com.h.backend.skill.infrastructure.gitee.GiteeSkillRepository;
import com.h.backend.skill.infrastructure.persistence.entity.SkillDefinitionEntity;
import com.h.backend.skill.infrastructure.persistence.mapper.*;
import com.h.backend.skill.infrastructure.validation.SkillContentValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** Real PostgreSQL schema, JSONB mapper SQL and transactions; remote source is deliberately
 * nontransactional so rollback/reconciliation and cross-instance serialization can be tested.
 */
@EnabledIfEnvironmentVariable(named = "SKILL_PG_CONTRACT", matches = "true")
class SkillNacosPersistenceContractTest {
    @Test
    void migrationPublishingIdempotencyConcurrentSaveAndRemoteSuccessRecovery() throws Exception {
        String schema = "skill_contract_" + UUID.randomUUID().toString().replace("-", "");
        String url = System.getenv("DB_URL");
        String user = System.getenv("DB_USERNAME");
        String password = System.getenv("DB_PASSWORD");
        try (Connection admin = DriverManager.getConnection(url, user, password)) {
            try (var statement = admin.createStatement()) { statement.execute("CREATE SCHEMA " + schema); }
            try {
                AbstractDataSource dataSource = new AbstractDataSource() {
                    @Override public Connection getConnection() throws SQLException {
                        Connection connection = DriverManager.getConnection(url, user, password);
                        connection.setSchema(schema);
                        return connection;
                    }
                    @Override public Connection getConnection(String ignoredUser, String ignoredPassword) throws SQLException { return getConnection(); }
                };
                try (Connection c = dataSource.getConnection(); var statement = c.createStatement()) {
                    statement.execute("CREATE TABLE users(id BIGINT PRIMARY KEY)");
                    statement.execute("INSERT INTO users(id) VALUES (7),(8)");
                    for (String migration : List.of("V20260828_02__create_skill_version_platform.sql", "V20261113_01__nacos_skill_sources.sql")) {
                        try (var in = getClass().getResourceAsStream("/db/migration/" + migration)) {
                            statement.execute(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                        }
                    }
                }
                var configuration = new MybatisConfiguration();
                configuration.setMapUnderscoreToCamelCase(true);
                for (Class<?> mapper : List.of(SkillDefinitionMapper.class, SkillProposalMapper.class, SkillReleaseMapper.class,
                        SkillPublicationOperationMapper.class, SkillOperationLogMapper.class)) configuration.addMapper(mapper);
                var factoryBean = new MybatisSqlSessionFactoryBean();
                factoryBean.setConfiguration(configuration);
                factoryBean.setDataSource(dataSource);
                factoryBean.afterPropertiesSet();
                var sql = new SqlSessionTemplate(factoryBean.getObject());
                var definitions = sql.getMapper(SkillDefinitionMapper.class);
                var proposals = sql.getMapper(SkillProposalMapper.class);
                var releases = sql.getMapper(SkillReleaseMapper.class);
                var publications = sql.getMapper(SkillPublicationOperationMapper.class);
                var logs = sql.getMapper(SkillOperationLogMapper.class);
                var source = mock(SkillSourceRepository.class);
                var artifacts = mock(SkillArtifactPublisher.class);
                var resolver = mock(SkillArtifactResolver.class);
                var legacy = mock(GiteeSkillRepository.class);
                var json = new ObjectMapper();
                var builder = new DeterministicSkillTarBuilder(json);
                var properties = new SkillPlatformProperties();
                var validator = new SkillContentValidator(new SkillContentValidator.Quotas(20, 1048576, 10485760, 200, 8));
                var transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
                var remote = new AtomicReference<SourceSnapshot>();
                when(source.openDraft(anyLong(), any())).thenAnswer(inv -> {
                    SkillFileSet files = inv.getArgument(1);
                    var snapshot = new SourceSnapshot("v1", DeterministicSkillTarBuilder.sha256Hex(builder.build(files)), null, files);
                    remote.set(snapshot);
                    return snapshot;
                });
                when(source.read(anyLong(), eq("v1"))).thenAnswer(inv -> remote.get());
                when(source.save(anyLong(), eq("v1"), anyString(), any())).thenAnswer(inv -> {
                    SkillFileSet files = inv.getArgument(3);
                    var snapshot = new SourceSnapshot("v1", DeterministicSkillTarBuilder.sha256Hex(builder.build(files)), inv.getArgument(2), files);
                    remote.set(snapshot);
                    return snapshot;
                });
                when(artifacts.storeVerifiedUserBundle(anyLong(), any())).thenAnswer(inv -> {
                    byte[] bytes = inv.getArgument(1);
                    return ArtifactDescriptor.of("sha256:" + DeterministicSkillTarBuilder.sha256Hex(bytes), bytes.length,
                            ArtifactDescriptor.USER_STORE, "test/digest", "object-version");
                });
                var catalog = new SkillCatalogService(definitions, proposals, releases, publications, logs, legacy, source,
                        validator, builder, artifacts, resolver, properties, json, transactions);
                long skillId = catalog.createSkill(7, new SkillCatalogService.CreateSkillCommand("test-skill", "Test", "desc", null)).id();
                var proposal = catalog.getProposal(7, skillId);
                assertThatThrownBy(() -> catalog.getProposal(8, skillId)).isInstanceOf(SkillPlatformException.class);

                // Two separate callers, one stale head: one remote mutation, one rejection.
                String expected = proposal.headCommitSha();
                var pool = Executors.newFixedThreadPool(2);
                var barrier = new CyclicBarrier(2);
                try {
                    Callable<Boolean> save = () -> {
                        barrier.await();
                        try {
                            catalog.saveProposal(7, skillId, expected, List.of(new SkillCatalogService.SaveProposalChange(
                                    "references/note.txt", Base64.getEncoder().encodeToString(Thread.currentThread().getName().getBytes(StandardCharsets.UTF_8)))));
                            return true;
                        } catch (SkillPlatformException ex) {
                            assertThat(ex.kind()).isEqualTo(SkillPlatformErrorKind.PROPOSAL_HEAD_MISMATCH);
                            return false;
                        }
                    };
                    var a = pool.submit(save); var b = pool.submit(save);
                    assertThat(List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS))).containsExactlyInAnyOrder(true, false);
                    verify(source, times(1)).save(eq(skillId), eq("v1"), eq(expected), any());
                } finally { pool.shutdownNow(); }

                // Remote success + PG rollback: the next refresh reconciles its direct child.
                var brokenProposals = spy(proposals);
                doThrow(new IllegalStateException("simulated PG failure after remote success")).when(brokenProposals).advanceHead(anyLong(), any(), any(), anyLong());
                var failingCatalog = new SkillCatalogService(definitions, brokenProposals, releases, publications, logs, legacy, source,
                        validator, builder, artifacts, resolver, properties, json, transactions);
                var beforeFailure = catalog.getProposal(7, skillId);
                assertThatThrownBy(() -> failingCatalog.saveProposal(7, skillId, beforeFailure.headCommitSha(), List.of(
                        new SkillCatalogService.SaveProposalChange("references/recovered.txt", "cmVjb3ZlcmVk"))))
                        .isInstanceOf(IllegalStateException.class);
                assertThat(proposals.selectOpenBySkillId(skillId).getHeadCommitSha()).isEqualTo(beforeFailure.headCommitSha());
                var recovered = catalog.getProposal(7, skillId);
                assertThat(recovered.headCommitSha()).isEqualTo(remote.get().revision());
                assertThat(recovered.headCommitSha()).isNotEqualTo(beforeFailure.headCommitSha());
                assertThat(recovered.validationStatus()).isEqualTo("UNVALIDATED");

                assertThat(catalog.validateProposal(7, skillId, recovered.headCommitSha()).valid()).isTrue();
                var release = catalog.publishRelease(7, skillId, recovered.headCommitSha(), recovered.headCommitSha(), "first", "contract-publish");
                assertThat(catalog.publishRelease(7, skillId, recovered.headCommitSha(), recovered.headCommitSha(), "first", "contract-publish").id()).isEqualTo(release.id());
                assertThat(releases.selectBySkillId(skillId)).hasSize(1);
                assertThat(proposals.selectOpenBySkillId(skillId)).isNull();
                SkillDefinitionEntity definition = definitions.selectOwnedById(skillId, 7);
                assertThat(definition.getEnabled()).isFalse();
                assertThat(definition.getActiveReleaseId()).isNull();
                assertThat(publications.selectByIdempotencyKey("contract-publish").getSourceCoordinatesJson()).contains("v1");
                assertThatThrownBy(() -> catalog.deleteSkill(7, skillId)).isInstanceOf(SkillPlatformException.class);
                // Convert this fixture to a legacy Release and verify in-place history import.
                try (Connection c = dataSource.getConnection(); var statement = c.createStatement()) {
                    statement.execute("UPDATE skill_releases SET source_provider = 'gitee', source_version = NULL, source_revision = NULL, "
                            + "commit_sha = '" + "a".repeat(40) + "', tag_name = 'legacy/v1' WHERE id = " + release.id());
                }
                var original = releases.selectBySkillAndId(skillId, release.id());
                when(resolver.openVerified(any())).thenAnswer(inv -> new VerifiedSkillBundle(inv.getArgument(0), remote.get().files(), null));
                when(source.importPublished(eq(skillId), eq("v1"), any())).thenAnswer(inv ->
                        new SourceSnapshot("v1", remote.get().revision(), null, inv.getArgument(2)));
                catalog.migrateSources(7, skillId);
                var imported = releases.selectBySkillAndId(skillId, release.id());
                assertThat(imported.getSourceProvider()).isEqualTo("nacos");
                assertThat(imported.getSourceVersion()).isEqualTo("v1");
                assertThat(imported.getId()).isEqualTo(original.getId());
                assertThat(imported.getArtifactDigest()).isEqualTo(original.getArtifactDigest());
                assertThat(imported.getArtifactObjectKey()).isEqualTo(original.getArtifactObjectKey());
                assertThat(imported.getCommitSha()).isEqualTo(original.getCommitSha());
                assertThat(imported.getTagName()).isEqualTo(original.getTagName());
                assertThat(definitions.selectOwnedById(skillId, 7).getActiveReleaseId()).isNull();
            } finally {
                try (var statement = admin.createStatement()) { statement.execute("DROP SCHEMA " + schema + " CASCADE"); }
            }
        }
    }
}
