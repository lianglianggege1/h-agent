package com.h.backend.chat.domain.subagentdefinition;

import com.h.backend.chat.domain.subagentdefinition.model.CreateSubagentDraftCommand;
import com.h.backend.chat.domain.subagentdefinition.model.DefinitionBinding;
import com.h.backend.chat.domain.subagentdefinition.model.DraftResult;
import com.h.backend.chat.domain.subagentdefinition.model.PublishResult;
import com.h.backend.chat.domain.subagentdefinition.model.ResolvedSubagentDefinition;
import com.h.backend.chat.domain.subagentdefinition.model.SaveSubagentDraftCommand;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentCatalogView;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionDetail;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionVersionDetail;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionVersionSummary;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentTurnSnapshot;
import com.h.backend.chat.domain.subagentdefinition.model.ValidateSubagentDraftCommand;
import com.h.backend.chat.domain.subagentdefinition.model.ValidationResult;

import java.util.List;

/**
 * Subagent Definition Catalog：本期核心深模块（设计 4.1）。
 *
 * <p>调用方只学习一套接口；内置来源、用户来源、草稿、发布事务、授权、quota、
 * 版本、编译和审计都留在实现内部。接口的不变量是接口的一部分：</p>
 *
 * <ul>
 *   <li>所有 USER Definition 操作都从认证 {@code userId} 推导 owner；调用方不能提交 owner；</li>
 *   <li>{@code requireVisible} 只返回系统内置或当前用户拥有的定义；跨用户统一表现为不存在；</li>
 *   <li>{@code saveDraft} / {@code publish} 使用 expected revision；过期 revision 冔回冲突，不覆盖；</li>
 *   <li>{@code publish} 只发布数据库中该 revision 的草稿，不接受请求内另一份 Markdown，
 *       避免校验与提交之间的 TOCTOU；</li>
 *   <li>{@code snapshotForTurn} 只返回系统启用定义和当前用户已发布且已启用的定义；</li>
 *   <li>{@code resolvePinned} 可以解析已停用或软删除定义的历史版本，
 *       但仍执行 owner 与 Session 归属校验。</li>
 * </ul>
 */
public interface SubagentDefinitionCatalog {

    /** 管理页列表：内置 + 当前用户定义 + 配额与能力摘要。 */
    SubagentCatalogView listForManagement(long userId);

    /** 详情：系统内置或当前用户拥有的定义；跨用户定义统一返回不存在。 */
    SubagentDefinitionDetail requireVisible(long userId, String agentId);

    /** 创建用户定义（有草稿、无发布版本、DISABLED）；agentId 创建后不可修改。 */
    DraftResult createDraft(long userId, CreateSubagentDraftCommand command);

    /** 保存草稿：expectedRevision 过期返回冲突；issues 不阻止保存。 */
    DraftResult saveDraft(long userId, String agentId, SaveSubagentDraftCommand command);

    /** 独立校验：不落库。 */
    ValidationResult validate(long userId, ValidateSubagentDraftCommand command);

    /** 发布当前 revision 的草稿：原子产生新版本并切换当前版本；hash 相同则幂等返回。 */
    PublishResult publish(long userId, String agentId, long expectedRevision);

    /** 启用/停用：只影响后续父 turn；启用要求存在当前发布版本且不超 enabled quota。 */
    SubagentDefinitionDetail setEnabled(long userId, String agentId, boolean enabled);

    /** 软删除：要求已停用；agent_id 永久保留，不允许复用为新身份。 */
    void softDelete(long userId, String agentId);

    /** 恢复软删除：不自动启用。 */
    SubagentDefinitionDetail restore(long userId, String agentId);

    /** 版本列表（含当前标记）。 */
    List<SubagentDefinitionVersionSummary> listVersions(long userId, String agentId);

    /** 版本详情：只读 Markdown 预览与编译结果。 */
    SubagentDefinitionVersionDetail versionDetail(long userId, String agentId, int version);

    /** 父 turn 开始时生成不可变 Catalog 快照。 */
    SubagentTurnSnapshot snapshotForTurn(long userId);

    /** 解析 child session 固定的 (definitionId, version)；跨 owner 返回不存在。 */
    ResolvedSubagentDefinition resolvePinned(long userId, DefinitionBinding binding);
}
