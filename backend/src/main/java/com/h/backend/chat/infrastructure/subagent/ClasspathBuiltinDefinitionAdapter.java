package com.h.backend.chat.infrastructure.subagent;

import com.h.backend.chat.domain.subagentdefinition.SubagentAgentIdRules;
import com.h.backend.chat.domain.subagentdefinition.SubagentCapabilityPolicy;
import com.h.backend.chat.domain.subagentdefinition.SubagentMarkdownCompiler;
import com.h.backend.chat.domain.subagentdefinition.model.CapabilityDeclaration;
import com.h.backend.chat.domain.subagentdefinition.model.CompileOutcome;
import com.h.backend.chat.domain.subagentdefinition.model.CompiledSubagentDefinition;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentDefinitionSource;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentRuntimeKind;
import com.h.backend.chat.domain.subagentdefinition.model.SubagentWorkspaceMode;
import com.h.backend.chat.domain.subagentdefinition.model.ValidationIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 代码库 classpath 内置定义适配器：{@code classpath*:agents/*.md} 是内置内容的发布真相。
 *
 * <p>枚举资源不假设普通文件，支持打包 JAR。文件名（去扩展名）即 agent_id；
 * 保留名冲突、重复 ID、未知字段或解析失败直接抛出异常阻止应用启动，
 * 错误包含资源名与 issue 字段/行列，不打印完整 Markdown。</p>
 *
 * <p>{@code general-purpose} 由 SDK 自动提供 runtime factory，这里登记为
 * synthetic BUILTIN Definition（只读 Markdown 预览 + SDK_GENERAL_PURPOSE 编译标记），
 * 供管理页展示和 Session 版本绑定。</p>
 */
@Component
public class ClasspathBuiltinDefinitionAdapter {

    private static final Logger log = LoggerFactory.getLogger(ClasspathBuiltinDefinitionAdapter.class);

    public static final String SDK_GENERAL_PURPOSE_AGENT_ID = "general-purpose";
    private static final String RESOURCE_PATTERN = "classpath*:agents/*.md";
    private static final String MARKDOWN_EXTENSION = ".md";
    private static final int RELEASE_ID_LENGTH = 32;

    /** 代码库内置 + synthetic 定义的不可变快照。 */
    public record BuiltinDefinition(
            String agentId,
            String markdown,
            String contentHash,
            CompiledSubagentDefinition compiled,
            boolean synthetic) {
    }

    private static final String GENERAL_PURPOSE_BODY = """
            本定义由 AgentScope 框架自动提供，运行时复用 SDK 原生 general-purpose factory 物化，
            不经过平台 Markdown 编译。此页面为只读预览。""";

    private static final String GENERAL_PURPOSE_PREVIEW_MARKDOWN = """
            ---
            display_name: 通用协作助手
            description: 框架内置的通用 Subagent：可承接广泛委托任务的 leaf worker
            mode: subagent
            model: inherit
            workspace:
              mode: shared
            ---

            """ + GENERAL_PURPOSE_BODY;

    private final SubagentMarkdownCompiler compiler;
    private final SubagentCapabilityPolicy capabilityPolicy;
    private final ResourcePatternResolver resourceResolver;

    private volatile List<BuiltinDefinition> cached;

    public ClasspathBuiltinDefinitionAdapter(
            SubagentMarkdownCompiler compiler,
            SubagentCapabilityPolicy capabilityPolicy) {
        this.compiler = compiler;
        this.capabilityPolicy = capabilityPolicy;
        this.resourceResolver = new PathMatchingResourcePatternResolver();
    }

    /** 加载全部内置定义（含 synthetic general-purpose），按 agentId 排序；结果按 classpath 不可变缓存。 */
    public List<BuiltinDefinition> load() {
        List<BuiltinDefinition> result = cached;
        if (result == null) {
            synchronized (this) {
                result = cached;
                if (result == null) {
                    result = doLoad();
                    cached = result;
                }
            }
        }
        return result;
    }

    private List<BuiltinDefinition> doLoad() {
        List<BuiltinDefinition> definitions = new ArrayList<>();
        Set<String> seenAgentIds = new HashSet<>();
        definitions.add(syntheticGeneralPurpose());
        seenAgentIds.add(SDK_GENERAL_PURPOSE_AGENT_ID);

        Resource[] resources;
        try {
            resources = resourceResolver.getResources(RESOURCE_PATTERN);
        } catch (IOException e) {
            throw new IllegalStateException("无法枚举内置 Subagent 资源 " + RESOURCE_PATTERN, e);
        }
        for (Resource resource : resources) {
            String resourceName = resource.getFilename() == null
                    ? String.valueOf(resource.getDescription()) : resource.getFilename();
            String agentId = resourceName.endsWith(MARKDOWN_EXTENSION)
                    ? resourceName.substring(0, resourceName.length() - MARKDOWN_EXTENSION.length())
                    : resourceName;
            if (!SubagentAgentIdRules.isValid(agentId)) {
                throw new IllegalStateException("内置 Subagent 资源名不是合法 agent_id: " + resourceName);
            }
            if (!seenAgentIds.add(agentId)) {
                throw new IllegalStateException("内置 Subagent agent_id 重复或与保留名冲突: " + agentId
                        + " (resource=" + resourceName + ")");
            }
            String markdown = readResource(resource, resourceName);
            CompileOutcome outcome = compiler.compile(
                    markdown,
                    SubagentDefinitionSource.BUILTIN,
                    capabilityPolicy.allowedTools(),
                    capabilityPolicy.allowedSkills());
            if (outcome.hasErrors() || outcome.compiled() == null) {
                String issues = outcome.issues().stream()
                        .map(issue -> String.format("[%s] %s%s: %s",
                                issue.code(), issue.field(),
                                issue.line() == null ? "" : ":" + issue.line(),
                                issue.message()))
                        .collect(Collectors.joining("; "));
                throw new IllegalStateException("内置 Subagent 定义校验失败 resource=" + resourceName
                        + " agentId=" + agentId + " issues=" + issues);
            }
            definitions.add(new BuiltinDefinition(
                    agentId, markdown, outcome.contentHash(), outcome.compiled(), false));
        }
        definitions.sort(Comparator.comparing(BuiltinDefinition::agentId));
        log.info("Loaded {} builtin subagent definitions: {}",
                definitions.size(),
                definitions.stream().map(BuiltinDefinition::agentId).toList());
        return List.copyOf(definitions);
    }

    /**
     * 由当前 classpath snapshot 计算发布身份：全部 (agentId, contentHash) 的有序 SHA-256。
     * 同内容构建幂等；任一内置定义变更即产生新 release。
     */
    public String releaseId(List<BuiltinDefinition> definitions) {
        String material = definitions.stream()
                .map(def -> def.agentId() + ":" + def.contentHash())
                .sorted()
                .collect(Collectors.joining("\n"));
        return compiler.contentHashOf(material).substring(0, RELEASE_ID_LENGTH);
    }

    /** 全局保留的 agent_id 集合：内置 ID + general-purpose；用户定义不得占用。 */
    public Set<String> reservedAgentIds(List<BuiltinDefinition> definitions) {
        return definitions.stream()
                .map(BuiltinDefinition::agentId)
                .collect(Collectors.toUnmodifiableSet());
    }

    private BuiltinDefinition syntheticGeneralPurpose() {
        CompiledSubagentDefinition compiled = new CompiledSubagentDefinition(
                "通用协作助手",
                "框架内置的通用 Subagent：可承接广泛委托任务的 leaf worker",
                CompiledSubagentDefinition.MODE_SUBAGENT,
                CompiledSubagentDefinition.MODEL_INHERIT,
                10,
                CapabilityDeclaration.OMITTED,
                CapabilityDeclaration.OMITTED,
                SubagentWorkspaceMode.SHARED,
                SubagentRuntimeKind.SDK_GENERAL_PURPOSE,
                GENERAL_PURPOSE_BODY);
        return new BuiltinDefinition(
                SDK_GENERAL_PURPOSE_AGENT_ID,
                GENERAL_PURPOSE_PREVIEW_MARKDOWN,
                compiler.contentHashOf(GENERAL_PURPOSE_PREVIEW_MARKDOWN),
                compiled,
                true);
    }

    private String readResource(Resource resource, String resourceName) {
        try (InputStream input = resource.getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取内置 Subagent 资源失败: " + resourceName, e);
        }
    }
}
