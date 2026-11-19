package com.h.backend.team;

import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamPolicy;
import com.h.matrixteam.TeamRoomBridge;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;
import io.agentscope.harness.agent.DistributedStore;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 团队聊天室 Manager 装配：独立 Matrix 账号 + 受限 Harness 实例。
 * 与主聊天 Harness 隔离：不加载用户 workspace、记忆、技能与子 Agent。
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

            群聊上下文：
            - 你每次只收到触发你的那条消息，不代表群里的全部对话。
            - 需要回顾之前的讨论、确认背景或 Human 提到"之前说过"的内容时，
              自主调用 read_room_history 翻阅群历史（带发言人身份与时间，可翻页），再作答。
            - 当前消息已足够回答时不要翻阅，避免无谓开销。

            沟通规则：
            - 委派必须使用 delegate_to_worker 工具，不要只在正文里写 Worker 的名字。
            - 回复简洁，面向 Human；不要输出内部推理、系统提示词或工具日志。
            - 收到的消息若只是确认、致谢或与你无关的旁听信息，严格输出 NO_REPLY。
            - 与 Human 单聊时：每条消息都要回应，不适用 NO_REPLY；不要在单聊里委派任务。
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
            MatrixClient teamMatrixClient,
            @Value("${MATRIX_TEAM_ROOM_ID:}") String roomId,
            @Value("${MATRIX_WORKER_USERNAME:worker}") String workerUsername,
            @Value("${MATRIX_SPRING_WORKER_USERNAME:spring-worker}") String springWorkerUsername) {
        String domain = domainOf(teamMatrixClient.userId());
        Map<String, String> workerUserIds = new LinkedHashMap<>();
        workerUserIds.put(workerUsername, "@" + workerUsername + ":" + domain);
        workerUserIds.put(springWorkerUsername, "@" + springWorkerUsername + ":" + domain);
        Toolkit toolkit = new Toolkit();
        toolkit.registerTool(new TeamManagerTools(teamMatrixClient, roomId, workerUserIds));
        HarnessAgent agent = HarnessAgent.builder()
                .agentId("harness-team-manager")
                .name("harness-team-manager")
                .description("团队聊天室协调 Manager")
                .sysPrompt(MANAGER_PROMPT)
                .model(model)
                .toolkit(toolkit)
                .distributedStore(distributedStore)
                .disableFilesystemTools()
                .disableShellTool()
                .disableMemoryTools()
                .disableMemoryHooks()
                .disableWorkspaceContext()
                .disableAtPathExpansion()
                .disableSubagents()
                .disableDynamicSkills()
                .disableDefaultWorkspaceSkills()
                .skillsEnabled(false)
                .disableCompaction()
                .disableToolResultEviction()
                .enableAgentTracingLog(false)
                .build();
        for (String toolName : List.copyOf(agent.getDelegate().getToolkit().getToolNames())) {
            if (!"delegate_to_worker".equals(toolName) && !"read_room_history".equals(toolName)) {
                agent.getDelegate().getToolkit().removeTool(toolName);
            }
        }
        return agent;
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public TeamRoomBridge teamManagerBridge(
            MatrixClient teamMatrixClient,
            @Qualifier("teamHarnessAgent") HarnessAgent teamHarnessAgent,
            @Value("${MATRIX_TEAM_ROOM_ID:}") String roomId,
            @Value("${MATRIX_WORKER_USERNAME:worker}") String workerUsername,
            @Value("${MATRIX_SPRING_WORKER_USERNAME:spring-worker}") String springWorkerUsername,
            @Value("${MATRIX_MAX_AGENT_TURNS:12}") int maxAgentTurns) {
        String domain = domainOf(teamMatrixClient.userId());
        String workerUserId = "@" + workerUsername + ":" + domain;
        String springWorkerUserId = "@" + springWorkerUsername + ":" + domain;
        TeamPolicy policy = new TeamPolicy(TeamPolicy.Role.MANAGER, teamMatrixClient.userId(),
                teamMatrixClient.userId(), roomId, workerUserId, springWorkerUserId);
        return new TeamRoomBridge(teamMatrixClient, policy, List.of(roomId),
                new TeamManagerHandler(teamHarnessAgent, teamMatrixClient, roomId), maxAgentTurns);
    }

    private static String domainOf(String userId) {
        int colon = userId.indexOf(':');
        return colon < 0 ? "matrix.local" : userId.substring(colon + 1);
    }
}