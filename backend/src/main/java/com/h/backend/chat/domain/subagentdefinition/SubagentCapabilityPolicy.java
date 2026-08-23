package com.h.backend.chat.domain.subagentdefinition;

import com.h.backend.chat.domain.subagentdefinition.model.CapabilityDeclaration;
import com.h.backend.chat.domain.subagentdefinition.model.CompiledSubagentDefinition;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 平台 Subagent 能力政策：计算 tools / skills 的有效交集，维护单调 policyRevision。
 *
 * <p>语义（不沿用 SDK “空列表表示继承全部”）：</p>
 * <ul>
 *   <li>字段省略：使用平台安全默认集合（tools 为四个只读文件工具，skills 为空）；</li>
 *   <li>显式空数组：明确为无能力；</li>
 *   <li>显式列表：取 声明集合 ∩ 平台允许集合 ∩ 父 DENY。</li>
 * </ul>
 *
 * <p>平台允许集合收紧时递增 policyRevision，使按
 * {@code (definitionId, version, policyRevision)} 缓存的编译结果自动失效；
 * 定义版本固定，但每次运行按当前政策重新求交集。</p>
 */
public final class SubagentCapabilityPolicy {

    /** 省略 tools 时的安全默认集合：只读文件工具。 */
    public static final List<String> DEFAULT_TOOLS =
            List.of("read_file", "grep_files", "glob_files", "list_files");

    /** 用户可显式申请的写工具。 */
    public static final List<String> REQUESTABLE_TOOLS =
            List.of("write_file", "edit_file");

    /** 第一期只支持继承父模型。 */
    public static final List<String> ALLOWED_MODELS = List.of(CompiledSubagentDefinition.MODEL_INHERIT);

    private final AtomicLong policyRevision;

    public SubagentCapabilityPolicy() {
        this.policyRevision = new AtomicLong(1);
    }

    /** 当前政策修订号；写入 turn snapshot 与 materialization 观测。 */
    public long policyRevision() {
        return policyRevision.get();
    }

    /** 平台允许的完整工具集合（默认 + 可申请）。 */
    public Set<String> allowedTools() {
        Set<String> tools = new LinkedHashSet<>(DEFAULT_TOOLS);
        tools.addAll(REQUESTABLE_TOOLS);
        return tools;
    }

    /** 编译期校验用的 Skill 允许集合；第一期平台未开放 Skill Catalog，恒为空。 */
    public Set<String> allowedSkills() {
        return Set.of();
    }

    /**
     * 运行时求有效能力交集。
     *
     * @param compiled   发布版本的编译结果
     * @param parentDeny 父 Agent 当前 DENY 集合；null 视为无额外 DENY
     * @return 有效 tools / skills；空列表表示无能力（不是继承全部）
     */
    public EffectiveCapabilities effective(CompiledSubagentDefinition compiled, Set<String> parentDeny) {
        List<String> tools = intersect(compiled.tools(), DEFAULT_TOOLS, allowedTools(), parentDeny);
        List<String> skills = intersect(compiled.skills(), List.of(), Set.of(), parentDeny);
        return new EffectiveCapabilities(tools, skills);
    }

    private List<String> intersect(
            CapabilityDeclaration declaration,
            List<String> platformDefault,
            Set<String> platformAllowed,
            Set<String> parentDeny) {
        List<String> candidates = switch (declaration.kind()) {
            case OMITTED -> platformDefault;
            case EMPTY -> List.of();
            case EXPLICIT -> declaration.names();
        };
        Set<String> deny = parentDeny == null ? Set.of() : parentDeny;
        List<String> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String name : candidates) {
            if (!platformAllowed.contains(name) || deny.contains(name)) {
                continue;
            }
            if (seen.add(name)) {
                result.add(name);
            }
        }
        return List.copyOf(result);
    }

    /** 交集计算结果：空列表表示无能力。 */
    public record EffectiveCapabilities(List<String> tools, List<String> skills) {

        public EffectiveCapabilities {
            tools = tools == null ? List.of() : List.copyOf(tools);
            skills = skills == null ? List.of() : List.copyOf(skills);
        }
    }
}
