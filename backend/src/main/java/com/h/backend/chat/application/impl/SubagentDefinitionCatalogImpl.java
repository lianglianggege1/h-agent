package com.h.backend.chat.application.impl;

import com.h.backend.chat.domain.subagentdefinition.SubagentAgentIdRules;
import com.h.backend.chat.domain.subagentdefinition.SubagentCapabilityPolicy;
import com.h.backend.chat.domain.subagentdefinition.SubagentDefinitionCatalog;
import com.h.backend.chat.domain.subagentdefinition.SubagentDefinitionException;
import com.h.backend.chat.domain.subagentdefinition.SubagentMarkdownCompiler;
import com.h.backend.chat.domain.subagentdefinition.SubagentQuotaPolicy;
import com.h.backend.chat.domain.subagentdefinition.model.CompileOutcome;
import com.h.backend.chat.domain.subagentdefinition.model.CompiledSubagentDefinition;
import com.h.backend.chat.domain.subagentdefinition.model.CreateSubagentDraftCommand;
import com.h.backend.chat.domain.subagentdefinition.model.DefinitionBinding;
import com.h.backend.chat.domain.subagentdefinition.model.DraftResult;
import com.h.backend.chat.domain.subagentdefinition.model.PublishResult;
import com.h.backend.chat.domain.subagentdefinition.model.ResolvedSubagentDefinition;
import com.h.backend.chat.domain.subagentdefinition.model.SaveSubagentDraftCommand;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentCatalogView;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionDetail;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionSource;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionSummary;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionVersionDetail;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionVersionSummary;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentTurnSnapshot;
import com.h.backend.chat.domain.subagentdefinition.model.ValidateSubagentDraftCommand;
import com.h.backend.chat.domain.subagentdefinition.model.ValidationIssue;
import com.h.backend.chat.domain.subagentdefinition.model.ValidationResult;
import com.h.backend.chat.infrastructure.persistence.entity.AgentDefinitionDraftEntity;
import com.h.backend.chat.infrastructure.persistence.entity.AgentDefinitionEntity;
import com.h.backend.chat.infrastructure.persistence.entity.AgentDefinitionVersionEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.AgentDefinitionAuditLogMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.AgentDefinitionDraftMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.AgentDefinitionMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.AgentDefinitionVersionMapper;
import com.h.backend.chat.infrastructure.subagent.ClasspathBuiltinDefinitionAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * {@link SubagentDefinitionCatalog} 的 PostgreSQL 实现。
 *
 * <p>事务与并发（设计 11）：</p>
 * <ul>
 *   <li>保存草稿使用条件更新 {@code WHERE revision = expected}，未命中即冲突；</li>
 *   <li>发布在单事务内锁定 Definition 与 Draft、重新编译（不信任保存时的
 *       validation cache）、hash 相同则幂等返回当前版本；</li>
 *   <li>createDraft / setEnabled 在用户级 advisory lock 下统计 quota，
 *       避免并发请求共同越过上限；</li>
 *   <li>启停、软删除、恢复都锁定 Definition 行后再翻转状态。</li>
 * </ul>
 *
 * <p>安全约束（设计 12）：所有 USER 查询都带 owner 条件，跨用户统一表现为不存在；
 * 日志与审计不保存 Markdown 正文；owner 由认证 userId 推导，不接受请求体提交。</p>
 */
@Service
public class SubagentDefinitionCatalogImpl implements SubagentDefinitionCatalog {

    private static final Logger log = LoggerFactory.getLogger(SubagentDefinitionCatalogImpl.class);

    /** 用户级 quota advisory lock 前缀；createDraft 与 setEnabled 共用同一把锁。 */
    private static final String USER_QUOTA_LOCK_PREFIX = "subagent-user-quota:";

    private final AgentDefinitionMapper definitionMapper;
    private final AgentDefinitionDraftMapper draftMapper;
    private final AgentDefinitionVersionMapper versionMapper;
    private final AgentDefinitionAuditLogMapper auditLogMapper;
    private final SubagentMarkdownCompiler compiler;
    private final SubagentCapabilityPolicy capabilityPolicy;
    private final SubagentQuotaPolicy quotaPolicy;
    private final ClasspathBuiltinDefinitionAdapter builtinAdapter;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public SubagentDefinitionCatalogImpl(
            AgentDefinitionMapper definitionMapper,
            AgentDefinitionDraftMapper draftMapper,
            AgentDefinitionVersionMapper versionMapper,
            AgentDefinitionAuditLogMapper auditLogMapper,
            SubagentMarkdownCompiler compiler,
            SubagentCapabilityPolicy capabilityPolicy,
            SubagentQuotaPolicy quotaPolicy,
            ClasspathBuiltinDefinitionAdapter builtinAdapter,
            TransactionTemplate transactionTemplate,
            ObjectMapper objectMapper) {
        this.definitionMapper = definitionMapper;
        this.draftMapper = draftMapper;
        this.versionMapper = versionMapper;
        this.auditLogMapper = auditLogMapper;
        this.compiler = compiler;
        this.capabilityPolicy = capabilityPolicy;
        this.quotaPolicy = quotaPolicy;
        this.builtinAdapter = builtinAdapter;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    // ---------- 查询 ----------

    @Override
    public SubagentCatalogView listForManagement(long userId) {
        List<AgentDefinitionEntity> builtins = definitionMapper.selectBuiltins();
        Map<Long, AgentDefinitionVersionEntity> currentVersions =
                indexVersionsByDefinitionId(loadCurrentBuiltinVersions(builtins));

        List<SubagentDefinitionSummary> system = builtins.stream()
                .map(def -> toSummary(def, currentVersions.get(def.getId()), null))
                .toList();

        List<AgentDefinitionEntity> owned = definitionMapper.selectOwnedByUser(userId);
        Map<Long, AgentDefinitionDraftEntity> drafts = new HashMap<>();
        for (AgentDefinitionDraftEntity draft : draftMapper.selectDraftsOwnedByUser(userId)) {
            drafts.put(draft.getDefinitionId(), draft);
        }
        Map<Long, AgentDefinitionVersionEntity> userCurrentVersions =
                indexVersionsByDefinitionId(loadCurrentUserVersions(owned));

        List<SubagentDefinitionSummary> mine = owned.stream()
                .map(def -> toSummary(def, userCurrentVersions.get(def.getId()), drafts.get(def.getId())))
                .toList();

        return new SubagentCatalogView(
                system,
                mine,
                new SubagentCatalogView.SubagentQuotaUsage(
                        SubagentQuotaPolicy.MAX_DEFINITIONS,
                        SubagentQuotaPolicy.MAX_ENABLED,
                        definitionMapper.countActiveOwnedByUser(userId),
                        definitionMapper.countEnabledOwnedByUser(userId)),
                new SubagentCatalogView.SubagentCapabilitySummary(
                        SubagentCapabilityPolicy.ALLOWED_MODELS,
                        SubagentCapabilityPolicy.DEFAULT_TOOLS,
                        SubagentCapabilityPolicy.REQUESTABLE_TOOLS));
    }

    private List<AgentDefinitionVersionEntity> loadCurrentBuiltinVersions(
            List<AgentDefinitionEntity> builtins) {
        List<AgentDefinitionVersionEntity> result = new ArrayList<>();
        for (AgentDefinitionEntity def : builtins) {
            if (def.getCurrentPublishedVersion() == null) {
                continue;
            }
            AgentDefinitionVersionEntity version = versionMapper.selectByDefinitionAndVersion(
                    def.getId(), def.getCurrentPublishedVersion());
            if (version == null) {
                log.warn("Builtin definition missing current version row definitionId={} version={}",
                        def.getId(), def.getCurrentPublishedVersion());
                continue;
            }
            result.add(version);
        }
        return result;
    }

    private List<AgentDefinitionVersionEntity> loadCurrentUserVersions(
            List<AgentDefinitionEntity> owned) {
        List<AgentDefinitionVersionEntity> result = new ArrayList<>();
        for (AgentDefinitionEntity def : owned) {
            if (def.getCurrentPublishedVersion() == null) {
                continue;
            }
            AgentDefinitionVersionEntity version = versionMapper.selectByDefinitionAndVersion(
                    def.getId(), def.getCurrentPublishedVersion());
            if (version == null) {
                log.warn("User definition missing current version row definitionId={} version={}",
                        def.getId(), def.getCurrentPublishedVersion());
                continue;
            }
            result.add(version);
        }
        return result;
    }

    private Map<Long, AgentDefinitionVersionEntity> indexVersionsByDefinitionId(
            List<AgentDefinitionVersionEntity> versions) {
        Map<Long, AgentDefinitionVersionEntity> index = new HashMap<>();
        for (AgentDefinitionVersionEntity version : versions) {
            index.put(version.getDefinitionId(), version);
        }
        return index;
    }

    /** 摘要：优先取当前发布版本的编译元数据；未发布定义尽力从草稿编译展示名。 */
    private SubagentDefinitionSummary toSummary(
            AgentDefinitionEntity def,
            AgentDefinitionVersionEntity currentVersion,
            AgentDefinitionDraftEntity draft) {
        String displayName = def.getAgentId();
        String description = "";
        if (currentVersion != null) {
            CompiledSubagentDefinition compiled = readCompiled(currentVersion);
            displayName = compiled.displayName();
            description = compiled.description();
        } else if (draft != null) {
            CompileOutcome outcome = compileUser(draft.getMarkdownContent());
            if (outcome.compiled() != null) {
                displayName = outcome.compiled().displayName();
                description = outcome.compiled().description();
            }
        }

        Long draftRevision = draft == null ? null : draft.getRevision();
        Boolean draftValid = draft == null ? null : !readIssues(draft.getValidationJson()).stream()
                .anyMatch(issue -> issue.severity() == ValidationIssue.Severity.ERROR);

        return new SubagentDefinitionSummary(
                def.getAgentId(),
                displayName,
                description,
                SubagentDefinitionSource.valueOf(def.getSource()),
                draftRevision,
                draftValid,
                def.getCurrentPublishedVersion(),
                Boolean.TRUE.equals(def.getEnabled()),
                def.getDeletedAt() != null,
                toInstant(def.getUpdatedAt()));
    }

    @Override
    public SubagentDefinitionDetail requireVisible(long userId, String agentId) {
        AgentDefinitionEntity builtin = definitionMapper.selectBuiltinByAgentId(agentId);
        if (builtin != null) {
            return toDetail(builtin);
        }
        AgentDefinitionEntity owned = requireOwnedUserDefinition(userId, agentId);
        return toDetail(owned);
    }

    private SubagentDefinitionDetail toDetail(AgentDefinitionEntity def) {
        AgentDefinitionVersionEntity currentVersion = null;
        if (def.getCurrentPublishedVersion() != null) {
            currentVersion = versionMapper.selectByDefinitionAndVersion(
                    def.getId(), def.getCurrentPublishedVersion());
        }
        AgentDefinitionDraftEntity draft = draftMapper.selectByDefinitionId(def.getId());

        return new SubagentDefinitionDetail(
                def.getAgentId(),
                def.getId(),
                SubagentDefinitionSource.valueOf(def.getSource()),
                def.getCurrentPublishedVersion(),
                currentVersion == null ? null : currentVersion.getMarkdownContent(),
                currentVersion == null ? null : currentVersion.getContentHash(),
                Boolean.TRUE.equals(def.getEnabled()),
                def.getDeletedAt() != null,
                draft == null ? null : draft.getRevision(),
                draft == null ? null : draft.getMarkdownContent(),
                draft == null ? List.of() : readIssues(draft.getValidationJson()),
                toInstant(def.getCreatedAt()),
                toInstant(def.getUpdatedAt()));
    }

    // ---------- 草稿 ----------

    @Override
    public DraftResult createDraft(long userId, CreateSubagentDraftCommand command) {
        String agentId = command.agentId();
        if (!SubagentAgentIdRules.isValid(agentId)) {
            throw new SubagentDefinitionException(SubagentDefinitionException.INVALID_AGENT_ID,
                    "agent_id 必须是 kebab-case，长度 1–63");
        }
        String markdown = normalizeMarkdown(command.markdown());

        return transactionTemplate.execute(status -> {
            Set<String> reserved = builtinAdapter.reservedAgentIds(builtinAdapter.load());
            if (reserved.contains(agentId)
                    || definitionMapper.selectBuiltinByAgentId(agentId) != null) {
                throw new SubagentDefinitionException(SubagentDefinitionException.RESERVED_AGENT_ID,
                        "agent_id 是系统保留名称: " + agentId);
            }

            definitionMapper.acquireAdvisoryLock(USER_QUOTA_LOCK_PREFIX + userId);

            AgentDefinitionEntity existing = definitionMapper.selectUserByAgentId(userId, agentId);
            if (existing != null) {
                if (existing.getDeletedAt() != null) {
                    throw new SubagentDefinitionException(SubagentDefinitionException.DEFINITION_DELETED,
                            "该 agent_id 已被删除的定义占用；可恢复原定义，但不能复用为新身份");
                }
                throw new SubagentDefinitionException(SubagentDefinitionException.DEFINITION_ALREADY_EXISTS,
                        "已存在同名定义: " + agentId);
            }
            if (!quotaPolicy.withinDefinitionLimit(definitionMapper.countActiveOwnedByUser(userId))) {
                throw new SubagentDefinitionException(SubagentDefinitionException.DEFINITION_LIMIT_EXCEEDED,
                        "定义数量已达上限 " + SubagentQuotaPolicy.MAX_DEFINITIONS);
            }

            // issues 不阻止保存；新建定义默认 有草稿、无发布版本、DISABLED。
            CompileOutcome outcome = compileUser(markdown);

            AgentDefinitionEntity def = new AgentDefinitionEntity();
            def.setSource(AgentDefinitionEntity.SOURCE_USER);
            def.setOwnerUserId(userId);
            def.setAgentId(agentId);
            def.setEnabled(false);
            definitionMapper.insert(def);

            AgentDefinitionDraftEntity draft = new AgentDefinitionDraftEntity();
            draft.setDefinitionId(def.getId());
            draft.setMarkdownContent(markdown);
            draft.setRevision(1L);
            draft.setValidationJson(writeIssues(outcome.issues()));
            draft.setUpdatedByUserId(userId);
            draftMapper.insert(draft);

            writeAudit(userId, def.getId(), null, 1L,
                    "CREATE_DRAFT", Map.of("agentId", agentId));

            return new DraftResult(agentId, def.getId(), 1L, outcome.issues());
        });
    }

    @Override
    public DraftResult saveDraft(long userId, String agentId, SaveSubagentDraftCommand command) {
        String markdown = normalizeMarkdown(command.markdown());

        return transactionTemplate.execute(status -> {
            AgentDefinitionEntity locked = lockOwnedUserDefinition(userId, agentId);
            if (locked.getDeletedAt() != null) {
                throw new SubagentDefinitionException(SubagentDefinitionException.DEFINITION_DELETED,
                        "定义已删除，不能编辑草稿；请先恢复");
            }

            // 保存只做语法检查并返回 issues；issues 不阻止保存。
            CompileOutcome outcome = compileUser(markdown);

            int updated = draftMapper.updateWithRevision(
                    locked.getId(), command.expectedRevision(), markdown,
                    writeIssues(outcome.issues()), userId);
            if (updated == 0) {
                throw new SubagentDefinitionException(SubagentDefinitionException.DRAFT_REVISION_CONFLICT,
                        "服务器草稿已变化（revision 不匹配），请刷新后重试");
            }
            long newRevision = command.expectedRevision() + 1;

            writeAudit(userId, locked.getId(), null, newRevision,
                    "SAVE_DRAFT", Map.of("agentId", agentId));

            return new DraftResult(agentId, locked.getId(), newRevision, outcome.issues());
        });
    }

    @Override
    public ValidationResult validate(long userId, ValidateSubagentDraftCommand command) {
        CompileOutcome outcome = compileUser(normalizeMarkdown(command.markdown()));
        return ValidationResult.of(outcome.issues());
    }

    // ---------- 发布与状态 ----------

    @Override
    public PublishResult publish(long userId, String agentId, long expectedRevision) {
        return transactionTemplate.execute(status -> {
            AgentDefinitionEntity locked = lockOwnedUserDefinition(userId, agentId);
            if (locked.getDeletedAt() != null) {
                throw new SubagentDefinitionException(SubagentDefinitionException.DEFINITION_DELETED,
                        "定义已删除，不能发布；请先恢复");
            }

            AgentDefinitionDraftEntity draft = draftMapper.selectByDefinitionIdForUpdate(locked.getId());
            if (draft == null) {
                throw new SubagentDefinitionException(SubagentDefinitionException.DEFINITION_NOT_FOUND,
                        "定义没有草稿: " + agentId);
            }
            if (draft.getRevision() != expectedRevision) {
                throw new SubagentDefinitionException(SubagentDefinitionException.DRAFT_REVISION_CONFLICT,
                        "服务器草稿已变化（revision 不匹配），请刷新后重试");
            }

            // 重新编译数据库中的草稿原文，不信任保存时的 validation cache（设计 11.2）。
            CompileOutcome outcome = compileUser(draft.getMarkdownContent());
            if (outcome.hasErrors() || outcome.compiled() == null) {
                throw new SubagentDefinitionException(
                        SubagentDefinitionException.PUBLISH_VALIDATION_FAILED,
                        "草稿存在校验错误，无法发布",
                        outcome.issues());
            }

            Integer currentVersion = locked.getCurrentPublishedVersion();
            if (currentVersion != null) {
                AgentDefinitionVersionEntity currentRow = versionMapper.selectByDefinitionAndVersion(
                        locked.getId(), currentVersion);
                if (currentRow != null && outcome.contentHash().equals(currentRow.getContentHash())) {
                    // 幂等发布：hash 与当前版本一致时不创建空版本。
                    writeAudit(userId, locked.getId(), currentVersion, draft.getRevision(),
                            "PUBLISH", Map.of(
                                    "agentId", agentId,
                                    "contentHash", outcome.contentHash(),
                                    "idempotent", true));
                    return new PublishResult(agentId, locked.getId(), currentVersion,
                            currentRow.getContentHash(), Boolean.TRUE.equals(locked.getEnabled()),
                            draft.getRevision(), outcome.compiled());
                }
            }

            int nextVersion = versionMapper.selectMaxVersion(locked.getId()) + 1;
            versionMapper.insertVersion(
                    locked.getId(),
                    nextVersion,
                    outcome.contentHash(),
                    draft.getMarkdownContent(),
                    writeCompiled(outcome.compiled()),
                    userId,
                    null);
            definitionMapper.updateCurrentVersionForward(locked.getId(), nextVersion);

            writeAudit(userId, locked.getId(), nextVersion, draft.getRevision(),
                    "PUBLISH", Map.of(
                            "agentId", agentId,
                            "contentHash", outcome.contentHash()));

            return new PublishResult(agentId, locked.getId(), nextVersion, outcome.contentHash(),
                    Boolean.TRUE.equals(locked.getEnabled()), draft.getRevision(), outcome.compiled());
        });
    }

    @Override
    public SubagentDefinitionDetail setEnabled(long userId, String agentId, boolean enabled) {
        return transactionTemplate.execute(status -> {
            // quota 统计必须发生在锁内，避免并发 enable 共同越过上限（设计 11.3）。
            definitionMapper.acquireAdvisoryLock(USER_QUOTA_LOCK_PREFIX + userId);

            AgentDefinitionEntity locked = lockOwnedUserDefinition(userId, agentId);
            if (locked.getDeletedAt() != null) {
                throw new SubagentDefinitionException(SubagentDefinitionException.DEFINITION_DELETED,
                        "定义已删除，请先恢复");
            }

            if (Boolean.valueOf(enabled).equals(locked.getEnabled())) {
                return toDetail(locked);
            }
            if (enabled) {
                if (locked.getCurrentPublishedVersion() == null) {
                    throw new SubagentDefinitionException(SubagentDefinitionException.NO_PUBLISHED_VERSION,
                            "定义没有发布版本，不能启用");
                }
                if (!quotaPolicy.withinEnabledLimit(
                        definitionMapper.countEnabledOwnedByUser(userId))) {
                    throw new SubagentDefinitionException(SubagentDefinitionException.ENABLED_LIMIT_EXCEEDED,
                            "已启用定义数已达上限 " + SubagentQuotaPolicy.MAX_ENABLED);
                }
            }

            definitionMapper.updateEnabled(locked.getId(), enabled);
            locked.setEnabled(enabled);

            writeAudit(userId, locked.getId(), locked.getCurrentPublishedVersion(), null,
                    enabled ? "ENABLE" : "DISABLE", Map.of("agentId", agentId));

            return toDetail(locked);
        });
    }

    @Override
    public void softDelete(long userId, String agentId) {
        transactionTemplate.executeWithoutResult(status -> {
            AgentDefinitionEntity locked = lockOwnedUserDefinition(userId, agentId);
            if (locked.getDeletedAt() != null) {
                throw new SubagentDefinitionException(SubagentDefinitionException.DEFINITION_DELETED,
                        "定义已删除");
            }
            if (Boolean.TRUE.equals(locked.getEnabled())) {
                throw new SubagentDefinitionException(SubagentDefinitionException.DELETE_REQUIRES_DISABLED,
                        "已启用的定义不能删除；请先停用");
            }
            definitionMapper.markDeleted(locked.getId());

            writeAudit(userId, locked.getId(), null, null,
                    "SOFT_DELETE", Map.of("agentId", agentId));
        });
    }

    @Override
    public SubagentDefinitionDetail restore(long userId, String agentId) {
        return transactionTemplate.execute(status -> {
            AgentDefinitionEntity locked = lockOwnedUserDefinition(userId, agentId);
            if (locked.getDeletedAt() == null) {
                return toDetail(locked);
            }
            definitionMapper.markRestored(locked.getId());

            writeAudit(userId, locked.getId(), null, null,
                    "RESTORE", Map.of("agentId", agentId));

            locked.setDeletedAt(null);
            return toDetail(locked);
        });
    }

    // ---------- 版本 ----------

    @Override
    public List<SubagentDefinitionVersionSummary> listVersions(long userId, String agentId) {
        AgentDefinitionEntity def = requireVisibleDefinition(userId, agentId);
        Integer current = def.getCurrentPublishedVersion();
        return versionMapper.selectByDefinitionId(def.getId()).stream()
                .map(version -> new SubagentDefinitionVersionSummary(
                        version.getVersion(),
                        version.getContentHash(),
                        toInstant(version.getCreatedAt()),
                        current != null && current == version.getVersion()))
                .toList();
    }

    @Override
    public SubagentDefinitionVersionDetail versionDetail(long userId, String agentId, int version) {
        AgentDefinitionEntity def = requireVisibleDefinition(userId, agentId);
        AgentDefinitionVersionEntity row = versionMapper.selectByDefinitionAndVersion(
                def.getId(), version);
        if (row == null) {
            throw new SubagentDefinitionException(SubagentDefinitionException.DEFINITION_NOT_FOUND,
                    "版本不存在: " + agentId + " v" + version);
        }
        Integer current = def.getCurrentPublishedVersion();
        return new SubagentDefinitionVersionDetail(
                agentId,
                def.getId(),
                row.getVersion(),
                row.getContentHash(),
                row.getMarkdownContent(),
                toInstant(row.getCreatedAt()),
                current != null && current == row.getVersion(),
                readCompiled(row));
    }

    // ---------- 运行时 ----------

    @Override
    public SubagentTurnSnapshot snapshotForTurn(long userId) {
        Map<String, ResolvedSubagentDefinition> byAgentId = new LinkedHashMap<>();

        for (AgentDefinitionEntity def : definitionMapper.selectBuiltins()) {
            if (!Boolean.TRUE.equals(def.getEnabled()) || def.getCurrentPublishedVersion() == null) {
                continue;
            }
            putResolved(byAgentId, def);
        }
        for (AgentDefinitionEntity def : definitionMapper.selectEnabledOwnedByUser(userId)) {
            putResolved(byAgentId, def);
        }

        return new SubagentTurnSnapshot(
                UUID.randomUUID().toString(),
                userId,
                Instant.now(),
                capabilityPolicy.policyRevision(),
                byAgentId);
    }

    private void putResolved(
            Map<String, ResolvedSubagentDefinition> byAgentId, AgentDefinitionEntity def) {
        AgentDefinitionVersionEntity version = versionMapper.selectByDefinitionAndVersion(
                def.getId(), def.getCurrentPublishedVersion());
        if (version == null) {
            log.warn("Snapshot definition missing current version row definitionId={} agentId={}",
                    def.getId(), def.getAgentId());
            return;
        }
        byAgentId.put(def.getAgentId(), toResolved(def, version));
    }

    @Override
    public ResolvedSubagentDefinition resolvePinned(long userId, DefinitionBinding binding) {
        if (binding == null || binding.definitionId() <= 0) {
            throw new SubagentDefinitionException(SubagentDefinitionException.DEFINITION_NOT_FOUND,
                    "缺少定义版本绑定");
        }
        AgentDefinitionVersionEntity version = versionMapper.selectByDefinitionAndVersion(
                binding.definitionId(), binding.version());
        if (version == null) {
            throw new SubagentDefinitionException(SubagentDefinitionException.DEFINITION_NOT_FOUND,
                    "定义版本不存在: definitionId=" + binding.definitionId()
                            + " version=" + binding.version());
        }
        AgentDefinitionEntity def = definitionMapper.selectById(version.getDefinitionId());
        if (def == null) {
            throw new SubagentDefinitionException(SubagentDefinitionException.DEFINITION_NOT_FOUND,
                    "定义不存在: definitionId=" + binding.definitionId());
        }
        // 跨 owner 访问统一表现为不存在（设计 12）；已停用/软删除的历史版本仍可解析。
        if (AgentDefinitionEntity.SOURCE_USER.equals(def.getSource())
                && !Long.valueOf(userId).equals(def.getOwnerUserId())) {
            throw new SubagentDefinitionException(SubagentDefinitionException.DEFINITION_NOT_FOUND,
                    "定义版本不存在: definitionId=" + binding.definitionId()
                            + " version=" + binding.version());
        }
        return toResolved(def, version);
    }

    private ResolvedSubagentDefinition toResolved(
            AgentDefinitionEntity def, AgentDefinitionVersionEntity version) {
        return new ResolvedSubagentDefinition(
                def.getId(),
                def.getAgentId(),
                SubagentDefinitionSource.valueOf(def.getSource()),
                version.getVersion(),
                version.getContentHash(),
                readCompiled(version));
    }

    // ---------- 内部工具 ----------

    private CompileOutcome compileUser(String markdown) {
        return compiler.compile(
                markdown,
                SubagentDefinitionSource.USER,
                capabilityPolicy.allowedTools(),
                capabilityPolicy.allowedSkills());
    }

    private AgentDefinitionEntity requireOwnedUserDefinition(long userId, String agentId) {
        AgentDefinitionEntity def = definitionMapper.selectUserByAgentId(userId, agentId);
        if (def == null) {
            throw notFound(agentId);
        }
        return def;
    }

    private AgentDefinitionEntity lockOwnedUserDefinition(long userId, String agentId) {
        AgentDefinitionEntity def = requireOwnedUserDefinition(userId, agentId);
        AgentDefinitionEntity locked = definitionMapper.selectByIdForUpdate(def.getId());
        if (locked == null) {
            throw notFound(agentId);
        }
        return locked;
    }

    private AgentDefinitionEntity requireVisibleDefinition(long userId, String agentId) {
        AgentDefinitionEntity def = definitionMapper.selectBuiltinByAgentId(agentId);
        if (def == null) {
            def = definitionMapper.selectUserByAgentId(userId, agentId);
        }
        if (def == null) {
            throw notFound(agentId);
        }
        return def;
    }

    private SubagentDefinitionException notFound(String agentId) {
        return new SubagentDefinitionException(SubagentDefinitionException.DEFINITION_NOT_FOUND,
                "定义不存在: " + agentId);
    }

    private String normalizeMarkdown(String markdown) {
        return markdown == null ? "" : markdown;
    }

    private CompiledSubagentDefinition readCompiled(AgentDefinitionVersionEntity version) {
        try {
            return objectMapper.readValue(
                    version.getCompiledMetadataJson(), CompiledSubagentDefinition.class);
        } catch (Exception e) {
            throw new IllegalStateException("解析编译结果失败 definitionId=" + version.getDefinitionId()
                    + " version=" + version.getVersion(), e);
        }
    }

    private String writeCompiled(CompiledSubagentDefinition compiled) {
        try {
            return objectMapper.writeValueAsString(compiled);
        } catch (Exception e) {
            throw new IllegalStateException("序列化编译结果失败", e);
        }
    }

    private List<ValidationIssue> readIssues(String validationJson) {
        if (validationJson == null || validationJson.isBlank()) {
            return List.of();
        }
        try {
            List<ValidationIssue> issues = objectMapper.readValue(
                    validationJson, new TypeReference<List<ValidationIssue>>() {
                    });
            return issues == null ? List.of() : issues;
        } catch (Exception e) {
            // 校验缓存损坏不应让管理页失败；降级为“未知校验状态”。
            log.warn("解析草稿校验缓存失败，降级为空 issues");
            return List.of();
        }
    }

    private String writeIssues(List<ValidationIssue> issues) {
        try {
            return objectMapper.writeValueAsString(issues == null ? List.of() : issues);
        } catch (Exception e) {
            throw new IllegalStateException("序列化校验结果失败", e);
        }
    }

    private void writeAudit(
            Long actorUserId, long definitionId, Integer version, Long revision,
            String operation, Map<String, Object> metadata) {
        // metadata 序列化失败降级为最小事实，不阻断状态变更；DB 写入失败则随事务回滚。
        String metadataJson;
        try {
            metadataJson = objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("审计 metadata 序列化失败 operation={}，降级为最小事实", operation);
            metadataJson = "{\"error\":\"metadata-serialization-failed\"}";
        }
        auditLogMapper.insertAudit(actorUserId, definitionId, version, revision, operation, null, metadataJson);
    }

    private static Instant toInstant(LocalDateTime time) {
        return time == null ? null : time.toInstant(ZoneOffset.UTC);
    }
}
