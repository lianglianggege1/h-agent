package com.h.backend.chat.infrastructure.subagent;

import tools.jackson.databind.ObjectMapper;
import com.h.backend.chat.infrastructure.config.SubagentCatalogProperties;
import com.h.backend.chat.infrastructure.persistence.entity.AgentDefinitionAuditLogEntity;
import com.h.backend.chat.infrastructure.persistence.entity.AgentDefinitionEntity;
import com.h.backend.chat.infrastructure.persistence.entity.AgentDefinitionVersionEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.AgentDefinitionAuditLogMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.AgentDefinitionMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.AgentDefinitionVersionMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;

/**
 * 启动期内置版本同步器：把代码库 classpath 的内置定义登记为可被 Session 引用的
 * 不可变 builtin version。
 *
 * <p>同步规则（设计 6.3/13）：</p>
 * <ul>
 *   <li>每个 agent_id 的事务级 advisory lock 串行化多节点登记；同一 release 复用同一 version 行；</li>
 *   <li>同一 release ID 对应不同 hash 时启动失败；</li>
 *   <li>新 release（包括正式回滚）创建更大的 version；current pointer 仅前向更新；</li>
 *   <li>内置定义恒为 enabled；登记新 version 时写 BUILTIN_SYNC 审计（不含正文）。</li>
 * </ul>
 *
 * <p>校验失败（含重复 ID、保留名冲突、未知字段）在加载阶段即抛出，阻止应用启动。</p>
 */
@Component
public class BuiltinVersionSynchronizer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BuiltinVersionSynchronizer.class);

    private static final String ADVISORY_LOCK_KEY_PREFIX = "subagent-builtin:";

    private final ClasspathBuiltinDefinitionAdapter adapter;
    private final AgentDefinitionMapper definitionMapper;
    private final AgentDefinitionVersionMapper versionMapper;
    private final AgentDefinitionAuditLogMapper auditLogMapper;
    private final TransactionTemplate transactionTemplate;
    private final SubagentCatalogProperties properties;
    private final ObjectMapper objectMapper;

    public BuiltinVersionSynchronizer(
            ClasspathBuiltinDefinitionAdapter adapter,
            AgentDefinitionMapper definitionMapper,
            AgentDefinitionVersionMapper versionMapper,
            AgentDefinitionAuditLogMapper auditLogMapper,
            TransactionTemplate transactionTemplate,
            SubagentCatalogProperties properties,
            ObjectMapper objectMapper) {
        this.adapter = adapter;
        this.definitionMapper = definitionMapper;
        this.versionMapper = versionMapper;
        this.auditLogMapper = auditLogMapper;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!properties.isEnabled()) {
            log.info("Subagent Definition Catalog disabled; builtin version sync skipped");
            return;
        }
        List<ClasspathBuiltinDefinitionAdapter.BuiltinDefinition> definitions = adapter.load();
        String releaseId = adapter.releaseId(definitions);
        log.info("Synchronizing builtin subagent definitions releaseId={} count={}",
                releaseId, definitions.size());
        for (ClasspathBuiltinDefinitionAdapter.BuiltinDefinition definition : definitions) {
            synchronize(definition, releaseId);
        }
    }

    private void synchronize(
            ClasspathBuiltinDefinitionAdapter.BuiltinDefinition definition, String releaseId) {
        Boolean created = transactionTemplate.execute(status -> {
            definitionMapper.acquireAdvisoryLock(
                    ADVISORY_LOCK_KEY_PREFIX + definition.agentId());

            AgentDefinitionEntity entity = definitionMapper.selectBuiltinByAgentId(definition.agentId());
            if (entity == null) {
                entity = new AgentDefinitionEntity();
                entity.setSource(AgentDefinitionEntity.SOURCE_BUILTIN);
                entity.setAgentId(definition.agentId());
                // CHECK 约束：enabled=true 需要 current_published_version 非空，先插入再登记版本。
                entity.setEnabled(false);
                definitionMapper.insert(entity);
            }

            AgentDefinitionVersionEntity version =
                    versionMapper.selectBuiltinByRelease(entity.getId(), releaseId);
            boolean inserted = false;
            int effectiveVersion;
            if (version == null) {
                effectiveVersion = versionMapper.selectMaxVersion(entity.getId()) + 1;
                versionMapper.insertVersion(
                        entity.getId(),
                        effectiveVersion,
                        definition.contentHash(),
                        definition.markdown(),
                        writeCompiled(definition),
                        null,
                        releaseId);
                inserted = true;
            } else {
                if (!definition.contentHash().equals(version.getContentHash())) {
                    throw new IllegalStateException(String.format(
                            "内置定义同一 release 出现不同 hash：agentId=%s releaseId=%s expected=%s actual=%s",
                            definition.agentId(), releaseId,
                            definition.contentHash(), version.getContentHash()));
                }
                effectiveVersion = version.getVersion();
            }

            definitionMapper.updateCurrentVersionForward(entity.getId(), effectiveVersion);
            definitionMapper.enableIfDisabled(entity.getId());

            if (inserted) {
                auditLogMapper.insertAudit(
                        null,
                        entity.getId(),
                        effectiveVersion,
                        null,
                        AgentDefinitionAuditLogEntity.OP_BUILTIN_SYNC,
                        null,
                        auditMetadata(releaseId, definition));
            }
            return inserted;
        });
        if (Boolean.TRUE.equals(created)) {
            log.info("Registered builtin subagent version agentId={} releaseId={}",
                    definition.agentId(), releaseId);
        }
    }

    private String writeCompiled(ClasspathBuiltinDefinitionAdapter.BuiltinDefinition definition) {
        try {
            return objectMapper.writeValueAsString(definition.compiled());
        } catch (Exception e) {
            throw new IllegalStateException(
                    "序列化内置定义编译结果失败 agentId=" + definition.agentId(), e);
        }
    }

    private String auditMetadata(
            String releaseId, ClasspathBuiltinDefinitionAdapter.BuiltinDefinition definition) {
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "releaseId", releaseId,
                    "agentId", definition.agentId(),
                    "contentHash", definition.contentHash(),
                    "synthetic", definition.synthetic()));
        } catch (Exception e) {
            // 审计 metadata 序列化失败不应阻断同步；返回最小事实。
            return "{\"releaseId\":\"" + releaseId + "\"}";
        }
    }
}
