package com.h.backend.chat.application.impl;

import com.h.backend.chat.domain.subagentdefinition.SubagentDefinitionCatalog;
import com.h.backend.chat.domain.subagentdefinition.SubagentDefinitionException;
import com.h.backend.chat.domain.subagentdefinition.model.CreateSubagentDraftCommand;
import com.h.backend.chat.domain.subagentdefinition.model.DefinitionBinding;
import com.h.backend.chat.domain.subagentdefinition.model.DraftResult;
import com.h.backend.chat.domain.subagentdefinition.model.PublishResult;
import com.h.backend.chat.domain.subagentdefinition.model.ResolvedSubagentDefinition;
import com.h.backend.chat.domain.subagentdefinition.model.SaveSubagentDraftCommand;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentCatalogView;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionDetail;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionSource;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionVersionDetail;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentTurnSnapshot;
import com.h.backend.chat.domain.subagentdefinition.model.ValidateSubagentDraftCommand;
import com.h.backend.chat.domain.subagentdefinition.model.ValidationResult;
import com.h.backend.user.infrastructure.persistence.entity.UserEntity;
import com.h.backend.user.infrastructure.persistence.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Catalog 接口集成测试（设计 15.2）：真实 PostgreSQL 约束 + 事务回滚隔离。
 *
 * <p>内置定义由 {@code BuiltinVersionSynchronizer} 在测试上下文启动时同步提交；
 * 用户定义数据在 @Transactional 测试内创建并回滚，不污染其他用例。</p>
 */
@SpringBootTest
@Transactional
class SubagentDefinitionCatalogImplTest {

    @Autowired
    private SubagentDefinitionCatalog catalog;

    @Autowired
    private UserMapper userMapper;

    private static final String VALID_MARKDOWN = """
            ---
            display_name: 我的资料整理员
            description: 阅读当前任务提供的资料并整理带出处的结论
            mode: subagent
            model: inherit
            steps: 10
            tools: [read_file, grep_files, glob_files, list_files]
            skills: []
            workspace:
              mode: isolated
            ---

            你是一名资料整理 Subagent。

            围绕父 Agent 的委托工作，不扩展任务范围；结论与证据分开陈述。
            """;

    private static final String VALID_MARKDOWN_V2 = """
            ---
            display_name: 我的资料整理员（第二版）
            description: 阅读当前任务提供的资料并整理带出处的结论（加强版）
            mode: subagent
            model: inherit
            steps: 12
            tools: [read_file, grep_files, glob_files, list_files, write_file]
            skills: []
            workspace:
              mode: isolated
            ---

            你是一名资料整理 Subagent（第二版）。

            围绕父 Agent 的委托工作，不扩展任务范围；结论与证据分开陈述，并给出下一步建议。
            """;

    /** 缺少必填 description：可保存为草稿，但发布必须失败。 */
    private static final String INVALID_MARKDOWN = """
            ---
            display_name: 我的资料整理员
            mode: subagent
            model: inherit
            ---

            你是一名资料整理 Subagent。
            """;

    private long newUser(String prefix) {
        UserEntity user = new UserEntity();
        user.setEmail(prefix + "-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("hash-value");
        user.setStatus((short) 1);
        userMapper.insert(user);
        return user.getId();
    }

    private DraftResult createValid(long userId, String agentId) {
        return catalog.createDraft(userId, new CreateSubagentDraftCommand(agentId, VALID_MARKDOWN));
    }

    @Test
    void listForManagementShowsBuiltinsAndIsolatesUsers() {
        long alice = newUser("alice");
        long bob = newUser("bob");

        createValid(alice, "my-reviewer");

        SubagentCatalogView aliceView = catalog.listForManagement(alice);
        SubagentCatalogView bobView = catalog.listForManagement(bob);

        assertThat(aliceView.system())
                .extracting(s -> s.agentId())
                .containsExactlyInAnyOrder(
                        "general-purpose", "planner", "researcher", "reviewer");
        assertThat(aliceView.system())
                .allSatisfy(s -> {
                    assertThat(s.source()).isEqualTo(SubagentDefinitionSource.BUILTIN);
                    assertThat(s.enabled()).isTrue();
                    assertThat(s.currentVersion()).isNotNull();
                });

        assertThat(aliceView.mine()).hasSize(1);
        assertThat(aliceView.mine().get(0).agentId()).isEqualTo("my-reviewer");
        assertThat(aliceView.mine().get(0).displayName()).isEqualTo("我的资料整理员");
        assertThat(aliceView.mine().get(0).enabled()).isFalse();

        // Bob 看不到 Alice 的定义。
        assertThat(bobView.mine()).isEmpty();

        assertThat(aliceView.limits().maxDefinitions()).isEqualTo(100);
        assertThat(aliceView.limits().maxEnabled()).isEqualTo(20);
        assertThat(aliceView.limits().usedDefinitions()).isEqualTo(1);
        assertThat(aliceView.capabilities().defaultTools())
                .containsExactly("read_file", "grep_files", "glob_files", "list_files");
        assertThat(aliceView.capabilities().requestableTools())
                .containsExactly("write_file", "edit_file");
    }

    @Test
    void createDraftRejectsInvalidAndReservedAgentIds() {
        long alice = newUser("alice");

        assertThatThrownBy(() -> catalog.createDraft(
                alice, new CreateSubagentDraftCommand("My_Reviewer", VALID_MARKDOWN)))
                .isInstanceOfSatisfying(SubagentDefinitionException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(SubagentDefinitionException.INVALID_AGENT_ID));

        for (String reserved : new String[]{"researcher", "reviewer", "planner", "general-purpose"}) {
            assertThatThrownBy(() -> catalog.createDraft(
                    alice, new CreateSubagentDraftCommand(reserved, VALID_MARKDOWN)))
                    .isInstanceOfSatisfying(SubagentDefinitionException.class,
                            e -> assertThat(e.getErrorCode())
                                    .isEqualTo(SubagentDefinitionException.RESERVED_AGENT_ID));
        }
    }

    @Test
    void sameAgentIdAllowedAcrossUsers() {
        long alice = newUser("alice");
        long bob = newUser("bob");

        createValid(alice, "shared-name");
        DraftResult bobResult = createValid(bob, "shared-name");

        assertThat(bobResult.revision()).isEqualTo(1L);
        assertThat(catalog.requireVisible(alice, "shared-name").definitionId())
                .isNotEqualTo(catalog.requireVisible(bob, "shared-name").definitionId());
    }

    @Test
    void invalidDraftSavesButCannotPublish() {
        long alice = newUser("alice");

        DraftResult created = catalog.createDraft(
                alice, new CreateSubagentDraftCommand("my-reviewer", INVALID_MARKDOWN));
        assertThat(created.hasErrors()).isTrue();

        SubagentDefinitionDetail detail = catalog.requireVisible(alice, "my-reviewer");
        assertThat(detail.draftRevision()).isEqualTo(1L);
        assertThat(detail.draftMarkdown()).isEqualTo(INVALID_MARKDOWN);
        assertThat(detail.currentVersion()).isNull();

        assertThatThrownBy(() -> catalog.publish(alice, "my-reviewer", 1L))
                .isInstanceOfSatisfying(SubagentDefinitionException.class, e -> {
                    assertThat(e.getErrorCode())
                            .isEqualTo(SubagentDefinitionException.PUBLISH_VALIDATION_FAILED);
                    assertThat(e.getIssues()).isNotEmpty();
                });

        // 修正草稿后可以发布。
        DraftResult saved = catalog.saveDraft(
                alice, "my-reviewer", new SaveSubagentDraftCommand(1L, VALID_MARKDOWN));
        assertThat(saved.revision()).isEqualTo(2L);
        assertThat(saved.hasErrors()).isFalse();

        PublishResult published = catalog.publish(alice, "my-reviewer", 2L);
        assertThat(published.version()).isEqualTo(1);
        assertThat(published.enabled()).isFalse();
    }

    @Test
    void saveDraftRevisionConflictDoesNotOverwrite() {
        long alice = newUser("alice");
        createValid(alice, "my-reviewer");

        catalog.saveDraft(alice, "my-reviewer",
                new SaveSubagentDraftCommand(1L, VALID_MARKDOWN_V2));

        assertThatThrownBy(() -> catalog.saveDraft(
                alice, "my-reviewer", new SaveSubagentDraftCommand(1L, INVALID_MARKDOWN)))
                .isInstanceOfSatisfying(SubagentDefinitionException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(SubagentDefinitionException.DRAFT_REVISION_CONFLICT));

        // 过期 revision 未覆盖：当前 revision 2 的内容仍是 V2。
        SubagentDefinitionDetail detail = catalog.requireVisible(alice, "my-reviewer");
        assertThat(detail.draftRevision()).isEqualTo(2L);
        assertThat(detail.draftMarkdown()).isEqualTo(VALID_MARKDOWN_V2);
    }

    @Test
    void publishIsIdempotentAndKeepsEnabled() {
        long alice = newUser("alice");
        createValid(alice, "my-reviewer");

        PublishResult v1 = catalog.publish(alice, "my-reviewer", 1L);
        assertThat(v1.version()).isEqualTo(1);
        assertThat(v1.compiled().displayName()).isEqualTo("我的资料整理员");

        catalog.setEnabled(alice, "my-reviewer", true);

        // 未修改草稿的重复发布（revision 仍为 1）：幂等返回当前版本，不创建空版本。
        PublishResult again = catalog.publish(alice, "my-reviewer", 1L);
        assertThat(again.version()).isEqualTo(1);
        assertThat(again.enabled()).isTrue();
        assertThat(catalog.listVersions(alice, "my-reviewer")).hasSize(1);

        // 修改草稿后发布 V2：版本递增、启用状态保持。
        catalog.saveDraft(alice, "my-reviewer",
                new SaveSubagentDraftCommand(1L, VALID_MARKDOWN_V2));
        PublishResult v2 = catalog.publish(alice, "my-reviewer", 2L);
        assertThat(v2.version()).isEqualTo(2);
        assertThat(v2.enabled()).isTrue();

        SubagentDefinitionDetail detail = catalog.requireVisible(alice, "my-reviewer");
        assertThat(detail.currentVersion()).isEqualTo(2);
        assertThat(detail.enabled()).isTrue();
        assertThat(catalog.listVersions(alice, "my-reviewer")).hasSize(2);
        assertThat(catalog.listVersions(alice, "my-reviewer").get(0).version()).isEqualTo(2);
        assertThat(catalog.listVersions(alice, "my-reviewer").get(0).current()).isTrue();

        SubagentDefinitionVersionDetail versionDetail =
                catalog.versionDetail(alice, "my-reviewer", 1);
        assertThat(versionDetail.markdown()).isEqualTo(VALID_MARKDOWN);
        assertThat(versionDetail.current()).isFalse();
        assertThat(versionDetail.compiled().displayName()).isEqualTo("我的资料整理员");
    }

    @Test
    void enableWithoutPublishedVersionFails() {
        long alice = newUser("alice");
        createValid(alice, "my-reviewer");

        assertThatThrownBy(() -> catalog.setEnabled(alice, "my-reviewer", true))
                .isInstanceOfSatisfying(SubagentDefinitionException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(SubagentDefinitionException.NO_PUBLISHED_VERSION));
    }

    @Test
    void snapshotForTurnContainsBuiltinsAndEnabledUserDefinitions() {
        long alice = newUser("alice");
        long bob = newUser("bob");
        createValid(alice, "my-reviewer");
        createValid(bob, "my-reviewer");

        // 未发布/未启用：不进入 snapshot。
        SubagentTurnSnapshot before = catalog.snapshotForTurn(alice);
        assertThat(before.byAgentId()).doesNotContainKey("my-reviewer");
        assertThat(before.byAgentId()).containsKeys(
                "general-purpose", "planner", "researcher", "reviewer");

        catalog.publish(alice, "my-reviewer", 1L);
        catalog.setEnabled(alice, "my-reviewer", true);

        SubagentTurnSnapshot snapshot = catalog.snapshotForTurn(alice);
        ResolvedSubagentDefinition resolved = snapshot.resolve("my-reviewer");
        assertThat(resolved).isNotNull();
        assertThat(resolved.version()).isEqualTo(1);
        assertThat(resolved.compiled().displayName()).isEqualTo("我的资料整理员");
        assertThat(resolved.source()).isEqualTo(SubagentDefinitionSource.USER);
        assertThat(snapshot.policyRevision()).isPositive();

        // Bob 同名定义未启用：Bob 的 snapshot 不包含；Alice 的 snapshot 不受 Bob 影响。
        assertThat(catalog.snapshotForTurn(bob).byAgentId()).doesNotContainKey("my-reviewer");

        // 停用后下一 snapshot 不可见。
        catalog.setEnabled(alice, "my-reviewer", false);
        assertThat(catalog.snapshotForTurn(alice).byAgentId()).doesNotContainKey("my-reviewer");
    }

    @Test
    void resolvePinnedEnforcesOwnerAndSurvivesStateChanges() {
        long alice = newUser("alice");
        long bob = newUser("bob");
        createValid(alice, "my-reviewer");
        PublishResult v1 = catalog.publish(alice, "my-reviewer", 1L);

        DefinitionBinding binding = DefinitionBinding.of(v1.definitionId(), 1);

        // 跨 owner 统一表现为不存在。
        assertThatThrownBy(() -> catalog.resolvePinned(bob, binding))
                .isInstanceOfSatisfying(SubagentDefinitionException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(SubagentDefinitionException.DEFINITION_NOT_FOUND));

        ResolvedSubagentDefinition resolved = catalog.resolvePinned(alice, binding);
        assertThat(resolved.agentId()).isEqualTo("my-reviewer");
        assertThat(resolved.version()).isEqualTo(1);

        // 发布 V2、停用、软删除后：pinned V1 仍解析原版本。
        catalog.saveDraft(alice, "my-reviewer",
                new SaveSubagentDraftCommand(1L, VALID_MARKDOWN_V2));
        catalog.publish(alice, "my-reviewer", 2L);
        catalog.setEnabled(alice, "my-reviewer", false);
        catalog.softDelete(alice, "my-reviewer");

        ResolvedSubagentDefinition stillV1 = catalog.resolvePinned(alice, binding);
        assertThat(stillV1.version()).isEqualTo(1);
        assertThat(stillV1.compiled().displayName()).isEqualTo("我的资料整理员");
    }

    @Test
    void softDeleteRequiresDisabledAndKeepsAgentIdReserved() {
        long alice = newUser("alice");
        createValid(alice, "my-reviewer");
        catalog.publish(alice, "my-reviewer", 1L);
        catalog.setEnabled(alice, "my-reviewer", true);

        assertThatThrownBy(() -> catalog.softDelete(alice, "my-reviewer"))
                .isInstanceOfSatisfying(SubagentDefinitionException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(SubagentDefinitionException.DELETE_REQUIRES_DISABLED));

        catalog.setEnabled(alice, "my-reviewer", false);
        catalog.softDelete(alice, "my-reviewer");

        SubagentDefinitionDetail deleted = catalog.requireVisible(alice, "my-reviewer");
        assertThat(deleted.deleted()).isTrue();
        assertThat(deleted.enabled()).isFalse();

        // 已删除 ID 不能复用为新身份。
        assertThatThrownBy(() -> createValid(alice, "my-reviewer"))
                .isInstanceOfSatisfying(SubagentDefinitionException.class,
                        e -> assertThat(e.getErrorCode())
                                .isEqualTo(SubagentDefinitionException.DEFINITION_DELETED));

        // 恢复后不自动启用。
        SubagentDefinitionDetail restored = catalog.restore(alice, "my-reviewer");
        assertThat(restored.deleted()).isFalse();
        assertThat(restored.enabled()).isFalse();
        assertThat(restored.currentVersion()).isEqualTo(1);
    }

    @Test
    void validateIsStatelessAndReportsIssues() {
        long alice = newUser("alice");

        ValidationResult valid = catalog.validate(
                alice, new ValidateSubagentDraftCommand(VALID_MARKDOWN));
        assertThat(valid.hasErrors()).isFalse();

        ValidationResult invalid = catalog.validate(
                alice, new ValidateSubagentDraftCommand(INVALID_MARKDOWN));
        assertThat(invalid.hasErrors()).isTrue();
        assertThat(invalid.issues())
                .anySatisfy(issue -> assertThat(issue.field()).isEqualTo("description"));

        // 内置定义对任意用户可见且只读。
        SubagentDefinitionDetail detail = catalog.requireVisible(alice, "researcher");
        assertThat(detail.source()).isEqualTo(SubagentDefinitionSource.BUILTIN);
        assertThat(detail.currentVersion()).isNotNull();
        assertThat(detail.draftRevision()).isNull();
    }
}
