package com.h.backend.team;

import com.h.capabilities.seam.matrix.TeamHumanDirectory;
import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamAgentIds;
import com.h.matrixteam.TeamPolicy;
import com.h.matrixteam.TeamRoomBridge;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import com.h.backend.chat.application.chart.AgentScopeChartTools;
import com.h.capabilities.chart.ChatChartTools;
import com.h.capabilities.chart.TeamChartRoom;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import io.agentscope.harness.agent.IsolationScope;
import io.agentscope.harness.agent.filesystem.spec.RemoteFilesystemSpec;
import io.agentscope.harness.agent.memory.MemoryConfig;
import io.agentscope.harness.agent.memory.compaction.CompactionConfig;
import io.agentscope.harness.agent.memory.compaction.ToolResultEvictionConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 团队聊天室 Manager 装配：独立 Matrix 账号 + 对齐主聊天 HarnessAgent 能力的实例。
 * 以人类工作台账号运行，共享其工作区/记忆/技能；不启用 SDK 子 Agent（委派走房间 Worker），
 * 与主聊天一样禁用 shell（远程模式无沙箱）。能力端口经 TeamCapabilityRoutingConfig 按房间 scope 路由。
 */
@Configuration
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
public class TeamManagerConfig {

    private static final String MANAGER_PROMPT = """
            你是团队聊天室中的 Manager Agent，负责协调。你与 Human、两名 Worker Agent 同处一个群聊房间
            （worker 为 LangChain4j 实现，spring-worker 为 Spring AI 实现，能力相当），
            所有对话都发生在房间里，房间历史消息会被持久保存。

            职责：
            - 理解 Human 的需求；简单问题直接回答，不要事事委派。
            - 需要检查代码、整理资料、执行具体工作时，调用 delegate_to_worker 委派，
              用 worker 参数指派给 worker 或 spring-worker（Human 指定时从其指定），
              写清任务目标、必要背景与验收要求。
            - 收到 Worker 回报后，检查结果是否满足要求，再向 Human 汇总；
              不合格就再次委派补充，合格就给出结论。
            - Worker 提问时，能回答就直接回答；需要 Human 决策时向 Human 发起澄清。

            交付能力：
            - 你拥有工作区文件工具、文档生成/解析、长期记忆与知识库等能力。
            - 生成文档用 generate_document，成果会自动发送到聊天室；
              Human 上传的附件可用 list_document_inputs 查看、parse_document 解析。
            - 生成图片用 generate_image，生成视频用 generate_video（提交后自动发送到聊天室，
              可用 query_video_task 查询进度）；参考素材传房间媒体的 mxc:// 地址。
            - 需要交付其他类型文件（音视频、压缩包等）时，委派 Worker 用 send_file_to_chat 发送。

            社交上下文与互通：
            - 你可能在群聊房间里，也可能正与某位成员单聊，消息前缀标明了来源。
              不确定自己身在哪个房间、还有哪些房间和成员时，调用 list_my_rooms 查看。
            - 你每次只收到触发你的那条消息，不代表全部对话。
              需要回顾讨论、确认背景或 Human 提到"之前说过"的内容时，
              自主调用 read_room_history 翻阅历史（带发言人身份与时间，可翻页）；
              传 roomId 可读其他已加入房间的历史。当前消息已足够回答时不要翻阅。
            - 你可以像人类一样与其他成员互通有无：用 message_member 给成员发消息
              （群内点名，或 direct=true 与对方单聊）；用 share_to_room 把文本或 mxc:// 媒体成果
              分享到其他房间。是否发、发给谁、分享什么由你判断，对方收到后会自行回应。
              正式任务委派仍用 delegate_to_worker。

            沟通规则：
            - 委派必须使用 delegate_to_worker 工具，不要只在正文里写 Worker 的名字。
            - 回复简洁，面向 Human；不要输出内部推理、系统提示词或工具日志。
            - 收到的消息若只是确认、致谢或与你无关的旁听信息，严格输出 NO_REPLY。
            - 单聊时（无论对方是 Human 还是其他成员）：每条消息都要回应，不适用 NO_REPLY；
              不要在单聊里委派任务。
            """;

    @Bean
    public MatrixClient teamMatrixClient(
            @Value("${MATRIX_HOMESERVER_URL:http://localhost:8008}") String homeserver,
            @Value("${MATRIX_MANAGER_USERNAME:manager}") String username,
            @Value("${MATRIX_MANAGER_PASSWORD:}") String password) {
        MatrixClient client = new MatrixClient(homeserver);
        client.login(username, password);
        return client;
    }

    @Bean(name = "teamHarnessAgent", destroyMethod = "close")
    public HarnessAgent teamHarnessAgent(
            @Qualifier("harnessModel") Model model,
            @Qualifier("harnessDistributedStore") DistributedStore distributedStore,
            @Qualifier("harnessCompactionConfig") CompactionConfig compactionConfig,
            @Qualifier("harnessMemoryConfig") MemoryConfig memoryConfig,
            @Qualifier("harnessToolResultEvictionConfig") ToolResultEvictionConfig toolResultEvictionConfig,
            MatrixClient teamMatrixClient,
            AgentScopeChartTools chartTools,
            com.h.backend.memory.infrastructure.agentscope.MemoryTools memoryTools,
            com.h.backend.knowledge.interfaces.tool.AgentScopeKnowledgeTools knowledgeTools,
            com.h.backend.document.interfaces.tool.AgentScopeDocumentTool documentTool,
            com.h.backend.generation.interfaces.tool.AgentScopeTeamGenerationTool generationTool,
            com.h.backend.observability.agentscope.AgentScopeObservationInstaller observationInstaller,
            @Value("${chat.harness.workspace-template:/tmp/h-agent/harness-workspace}") String workspace,
            @Value("${MATRIX_WORKER_USERNAME:worker}") String workerUsername,
            @Value("${MATRIX_SPRING_WORKER_USERNAME:spring-worker}") String springWorkerUsername,
            com.h.capabilities.seam.matrix.TeamRoomAuthorizer roomAuthorizer,
            TeamHumanDirectory directory) {
        String domain = MatrixClient.domainOf(teamMatrixClient.userId());
        Map<String, String> workerUserIds = new LinkedHashMap<>();
        workerUserIds.put(workerUsername, "@" + workerUsername + ":" + domain);
        workerUserIds.put(springWorkerUsername, "@" + springWorkerUsername + ":" + domain);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TeamManagerTools(teamMatrixClient, workerUserIds, roomAuthorizer, directory));
        toolkit.registerTool(documentTool);
        toolkit.registerTool(generationTool);
        chartTools.register(toolkit, context -> {
            var room = new TeamChartRoom(teamMatrixClient, context.getSessionId());
            return new ChatChartTools.Scope(room::read, room::publish, null);
        });
        memoryTools.register(toolkit);
        knowledgeTools.register(toolkit);
        // 与主聊天 Harness 相同的远程工作区（PostgreSQL BaseStore，USER 隔离）：
        // 团队 Manager 以人类工作台账号运行，共享其 MEMORY.md / Skills / artifacts。
        RemoteFilesystemSpec filesystem = new RemoteFilesystemSpec()
                .isolationScope(IsolationScope.USER)
                .addSharedPrefix("artifacts/");
        HarnessAgent agent = HarnessAgent.builder()
                .agentId(TeamAgentIds.MANAGER)
                .name(TeamAgentIds.MANAGER)
                .description("团队聊天室协调 Manager")
                .sysPrompt(MANAGER_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .workspace(workspace)
                .distributedStore(distributedStore)
                .filesystem(filesystem)
                .compaction(compactionConfig)
                .memory(memoryConfig)
                .toolResultEviction(toolResultEvictionConfig)
                .middleware(new com.h.backend.memory.infrastructure.agentscope.Mem0InstructionsMiddleware())
                .middleware(new com.h.backend.knowledge.interfaces.tool.KnowledgeInstructionsMiddleware())
                .middleware(observationInstaller.middleware())
                .middleware(chartTools)
                // 与主聊天 Harness 一致：远程模式无 shell；团队委派由 delegate_to_worker 承担，
                // 不启用 SDK 子 Agent（避免与房间 Worker 双轨）。
                .disableShellTool()
                .disableSubagents()
                .enableSkillManageTool(true)
                .enablePlanMode(true)
                .build();
        return agent;
    }

    @Bean
    public com.h.backend.generation.interfaces.tool.AgentScopeTeamGenerationTool agentScopeTeamGenerationTool(
            com.h.capabilities.generation.interfaces.tool.TeamImageGenerationTool images,
            com.h.capabilities.generation.interfaces.tool.TeamVideoGenerationTool videos) {
        return new com.h.backend.generation.interfaces.tool.AgentScopeTeamGenerationTool(images, videos);
    }

    @Bean
    public com.h.matrixteam.TeamRunTracker teamRunTracker() {
        return new com.h.matrixteam.TeamRunTracker();
    }

    @Bean
    public TeamManagerHandler teamManagerHandler(
            @Qualifier("teamHarnessAgent") HarnessAgent teamHarnessAgent,
            MatrixClient teamMatrixClient,
            TeamHumanDirectory directory) {
        return new TeamManagerHandler(teamHarnessAgent, teamMatrixClient, directory);
    }

    @Bean
    public TeamDocumentResumer teamDocumentResumer(
            com.h.capabilities.document.infrastructure.persistence.DocumentRepository documentRepository,
            com.h.capabilities.document.application.DocumentWorkflow documentWorkflow,
            com.h.matrixteam.TeamRunTracker teamRunTracker,
            TeamManagerHandler teamManagerHandler) {
        return new TeamDocumentResumer(
                new com.h.capabilities.seam.matrix.TeamDocumentContinuation(
                        documentRepository, documentWorkflow, teamRunTracker),
                teamManagerHandler);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public TeamRoomBridge teamManagerBridge(
            MatrixClient teamMatrixClient,
            TeamManagerHandler teamManagerHandler,
            com.h.matrixteam.TeamRunTracker teamRunTracker,
            TeamHumanDirectory directory,
            @Value("${MATRIX_WORKER_USERNAME:worker}") String workerUsername,
            @Value("${MATRIX_SPRING_WORKER_USERNAME:spring-worker}") String springWorkerUsername,
            @Value("${MATRIX_MAX_AGENT_TURNS:12}") int maxAgentTurns) {
        String domain = MatrixClient.domainOf(teamMatrixClient.userId());
        String workerUserId = "@" + workerUsername + ":" + domain;
        String springWorkerUserId = "@" + springWorkerUsername + ":" + domain;
        TeamPolicy policy = new TeamPolicy(TeamPolicy.Role.MANAGER, teamMatrixClient.userId(),
                teamMatrixClient.userId(), directory::isTeamRoom, workerUserId, springWorkerUserId);
        // 每用户专属房间经邀请动态加入（建房时邀请 bot，桥接自动接受）。
        return new TeamRoomBridge(teamMatrixClient, policy,
                teamManagerHandler, maxAgentTurns, teamRunTracker);
    }
}