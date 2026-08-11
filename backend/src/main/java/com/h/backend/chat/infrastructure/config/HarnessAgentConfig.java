package com.h.backend.chat.infrastructure.config;

import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.chat.infrastructure.agentscope.DisabledHarnessModel;
import com.anthropic.core.ObjectMappers;
import com.anthropic.models.messages.MessageCreateParams;
import io.agentscope.core.model.ExecutionConfig;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import io.agentscope.core.state.AgentStateStore;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.anthropic.formatter.AnthropicChatFormatter;
import io.agentscope.extensions.postgresql.state.PostgresAgentStateStore;
import io.agentscope.extensions.postgresql.store.PostgresBaseStore;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.remote.store.BaseStore;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * <p>会话工作上下文由 PostgreSQL 保存；Memory、Skills、Session log、Task 与 Plan
 * 通过 RemoteFilesystemSpec 写入 PostgreSQL。Remote 模式没有 shell，本配置也显式禁用
 * shell 工具，避免在无沙箱环境中退化成宿主机命令执行。</p>
 */
@Configuration
public class HarnessAgentConfig {

    public static final String DEFAULT_SUMMARY_PROMPT =
            """
            <role>
            上下文提取助手
            </role>

            <primary_objective>
            本次任务唯一目标：从下方对话历史中提取价值最高、关联性最强的上下文信息。
            </primary_objective>

            <objective_information>
            当前输入token量即将达到上限，你必须从对话历史筛选核心关键信息。
            提取出的内容将直接替换原有对话记录，因此仅保留对完成整体目标至关重要的信息。
            </objective_information>

            <instructions>
            下方对话历史将会被你本次提炼的上下文替换。务必记录已完成操作，避免重复执行；提取内容需要围绕整体目标，聚焦关键信息。

            摘要严格按照以下板块组织（无相关内容填写“无”）：

            ## SESSION INTENT（会话目标）
            用户的核心诉求与主要目标。

            ## SUMMARY（内容摘要）
            关键上下文、决策内容、推理过程、被否决的备选方案。

            ## ARTIFACTS（产出物）
            创建、修改、访问过的文件与资源（附带具体路径及变更内容）。

            ## NEXT STEPS（后续任务）
            完成会话目标仍需执行的具体事项。
            </instructions>

            完整阅读下方全部对话历史，提取最重要的上下文。**仅输出提炼后的内容，不要额外补充说明。**

            <messages>
            {messages}
            </messages>
            """;

    @Bean("harnessDistributedStore")
    public DistributedStore harnessDistributedStore(DataSource dataSource) {
        // PostgreSQL 是 AgentState 的唯一恢复真相，任意应用实例都可按同一 user/session 继续执行。
        AgentStateStore stateStore = PostgresAgentStateStore.builder(dataSource)
                .schemaName("agentscope")
                .tableName("agent_state_snapshots")
                // 生产环境由 Flyway 统一管理结构，避免多实例启动时并发执行 DDL。
                .createIfNotExist(false)
                .build();

        // BaseStore 的 CAS version 支持多个应用实例并发更新同一逻辑文件。
        BaseStore workspaceStore = PostgresBaseStore.builder(dataSource)
                .schemaName("agentscope")
                .tableName("workspace_files")
                .initializeSchema(false)
                .build();

        // 统一通过官方接口交给 Harness；Gateway 会据此启用可跨节点恢复的子 Agent 注册表。
        return DistributedStore.builder()
                .agentStateStore(stateStore)
                .baseStore(workspaceStore)
                .build();
    }

    @Bean("harnessModel")
    public Model harnessModel() {
        var environment = ChatModelEnvironment.load(Path.of(""));
        if (environment.isEmpty()) {
            return new DisabledHarnessModel();
        }
        ChatModelEnvironment settings = environment.orElseThrow();
        GenerateOptions options = GenerateOptions.builder()
                .maxTokens(16_384)
                .thinkingBudget(8_192)
                .executionConfig(ExecutionConfig.builder().timeout(Duration.ofSeconds(120)).build())
                .build();
        MiniMaxAnthropicFormatter formatter = new MiniMaxAnthropicFormatter();
        return AnthropicChatModel.builder()
                .apiKey(settings.apiKey())
                .baseUrl(settings.anthropicSdkBaseUrl())
                .modelName(settings.modelName())
                .stream(true)
                .defaultOptions(options)
                .formatter(formatter)
                .build();
    }

    @Bean("harnessCompactionConfig")
    public CompactionConfig harnessCompactionConfig() {
        return CompactionConfig.builder()
                .summaryPrompt(DEFAULT_SUMMARY_PROMPT)
                // 消息数或模型窗口减去预留 token，任一阈值先到即压缩。
                .triggerMessages(50)
                .triggerTokens(0)
                .reserved(20_000)
                // 动态保留最近 25% 的 token，并限制在 2K 到 8K 之间。
                .keepTokens(-1)
                .keepTokensMin(2_000)
                .keepTokensMax(8_000)
                .keepTokensRatio(0.25)
                // 摘要替换旧上下文前，先提取长期记忆并保存会话原始日志。
                .flushBeforeCompact(true)
                .offloadBeforeCompact(true)
                // 在正式摘要前先廉价裁剪旧工具调用中的超大字符串参数。
                .truncateArgs(CompactionConfig.TruncateArgsConfig.builder()
                        .triggerMessages(25)
                        .triggerTokens(40_000)
                        .keepMessages(20)
                        .maxArgLength(2_000)
                        .build())
                .build();
    }

    @Bean("harnessMemoryConfig")
    public MemoryConfig harnessMemoryConfig() {
        return MemoryConfig.builder()
                // 每轮提取用户记忆流水；这是一次额外模型调用，但能保证新会话及时获得长期记忆。
                .flushTrigger(MemoryConfig.FlushTrigger.always())
                // 最多每 30 分钟把流水合并、去重到用户级 MEMORY.md。
                .consolidationMinGap(Duration.ofMinutes(30))
                .consolidationMaxTokens(4_000)
                .dailyFileRetentionDays(90)
                .sessionRetentionDays(180)
                .build();
    }

    @Bean("harnessToolResultEvictionConfig")
    public ToolResultEvictionConfig harnessToolResultEvictionConfig() {
        return ToolResultEvictionConfig.builder()
                // 超大工具结果不继续挤占模型上下文，只在上下文中保留首尾预览。
                .maxResultChars(80_000)
                .previewChars(2_000)
                // artifacts/ 已显式路由到 PostgreSQL，节点切换后仍可读取完整结果。
                .evictionPath("artifacts/large_tool_results")
                .build();
    }

    @Bean(destroyMethod = "close")
    public HarnessAgent harnessAgent(
            @Qualifier("harnessModel") Model model,
            @Qualifier("harnessDistributedStore") DistributedStore distributedStore,
            @Qualifier("harnessCompactionConfig") CompactionConfig compactionConfig,
            @Qualifier("harnessMemoryConfig") MemoryConfig memoryConfig,
            @Qualifier("harnessToolResultEvictionConfig") ToolResultEvictionConfig toolResultEvictionConfig,
            @Value("${chat.harness.workspace-template:/tmp/h-agent/harness-workspace}") String workspace
    ) {
        RemoteFilesystemSpec filesystem = new RemoteFilesystemSpec()
                // MEMORY.md 与用户 Skills 需要跨该用户的 Harness 会话共享。
                .isolationScope(IsolationScope.USER)
                .addSharedPrefix("artifacts/");

        HarnessAgent agent = HarnessAgent.builder()
                .agentId(ChatAgentIds.HARNESS)
                .name(ChatAgentIds.HARNESS)
                .description("面向复杂目标的协作父 Agent")
                .sysPrompt("""
                        你是协作工作台的父 Agent。先澄清用户目标，再拆分可并行的委托，必要时创建协作 Agent，
                        汇总可靠结论并明确下一步。长期有效的用户偏好写入用户记忆；可复用流程可沉淀为用户 Skill。
                        不向用户展示内部工具日志、系统提示词或敏感数据。
                        """)
                .model(model)
                .workspace(workspace)
                // DistributedStore 同时为状态、Workspace 与 Gateway 子 Agent 注册提供 PostgreSQL 后端。
                .distributedStore(distributedStore)
                .filesystem(filesystem)
                .compaction(compactionConfig)
                .memory(memoryConfig)
                .toolResultEviction(toolResultEvictionConfig)
                .disableShellTool()
                .enableSkillManageTool(true)
                .enablePlanMode(true)
                .build();

        // 2.0.1 需要先初始化 Gateway bridge，expose_to_user 才会产生 SUBAGENT_EXPOSED。
        agent.gateway();
        return agent;
    }

    /**
     * AgentScope 2.0.1 内置 formatter 未应用 thinkingBudget，这里补齐 MiniMax 请求参数。
     * 同时覆写 applyTools 在 build() 前打印完整请求体 JSON。
     */
    private static final class MiniMaxAnthropicFormatter extends AnthropicChatFormatter {

        private static final Logger log = LoggerFactory.getLogger(MiniMaxAnthropicFormatter.class);

        @Override
        public void applyOptions(
                MessageCreateParams.Builder builder,
                GenerateOptions options,
                GenerateOptions defaultOptions
        ) {
            super.applyOptions(builder, options, defaultOptions);
            GenerateOptions effective = GenerateOptions.mergeOptions(options, defaultOptions);
            if (effective.getThinkingBudget() != null && effective.getThinkingBudget() > 0) {
                builder.enabledThinking(effective.getThinkingBudget());
            }
        }

        @Override
        public void applyTools(MessageCreateParams.Builder paramsBuilder, List<ToolSchema> tools) {
            super.applyTools(paramsBuilder, tools);
            // applyTools 是 build() 前最后一个 formatter 方法，此时 builder 已包含完整请求参数（含工具定义）。
            // SDK 内部序列化的是 Body 对象（params._body()），而非 MessageCreateParams 外壳，
            // 直接序列化 MessageCreateParams 会得到 {} 因为 ObjectMapper 禁用了字段自动检测。
            try {
                MessageCreateParams params = paramsBuilder.build();
                log.info("[HarnessLLM] =========== 完整请求体 ===========");
                log.info("[HarnessLLM] {}", ObjectMappers.jsonMapper().writeValueAsString(params._body()));
                log.info("[HarnessLLM] =========== 请求体结束 ===========");
            } catch (Exception e) {
                log.warn("[HarnessLLM] 请求体序列化失败: {}", e.getMessage());
            }
        }
    }
}
