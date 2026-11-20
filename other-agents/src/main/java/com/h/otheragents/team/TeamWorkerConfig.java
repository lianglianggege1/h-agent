package com.h.otheragents.team;

import com.h.backend.chat.application.FileDeliveryService;
import com.h.backend.chat.application.WebSearchService;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.chat.infrastructure.filesystem.AssistantFileStorage;
import com.h.backend.chat.infrastructure.tools.ShellExecutionService;
import com.h.backend.document.application.DocumentWorkflow;
import com.h.backend.generation.interfaces.tool.TeamImageGenerationTool;
import com.h.backend.generation.interfaces.tool.TeamVideoGenerationTool;
import com.h.backend.memory.application.AgentMemoryContext;
import com.h.backend.memory.domain.MemoryInvocationContext;
import com.h.backend.skill.application.SkillRuntimeService;
import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamMessage;
import com.h.matrixteam.TeamPolicy;
import com.h.matrixteam.TeamProtocol;
import com.h.matrixteam.TeamRoomBridge;
import com.h.otheragents.team.capability.TeamIdentity;
import com.h.otheragents.team.tool.langchain4j.WorkerDeliveryTools;
import com.h.otheragents.team.tool.langchain4j.WorkerDocumentTools;
import com.h.otheragents.team.tool.langchain4j.WorkerFilesystemTools;
import com.h.otheragents.team.tool.langchain4j.WorkerGenerationTools;
import com.h.otheragents.team.tool.langchain4j.WorkerKnowledgeToolProvider;
import com.h.otheragents.team.tool.langchain4j.WorkerMemoryToolProvider;
import com.h.otheragents.team.tool.langchain4j.WorkerSearchTools;
import com.h.otheragents.team.tool.langchain4j.WorkerShellTools;
import com.h.otheragents.team.tool.langchain4j.WorkerSkillToolProvider;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

/**
 * 团队聊天室 Worker 装配：独立 Matrix 账号 + LangChain4j Agent。
 * 只在被 Manager 委派或被明确提及时执行，结果以结构化回报发回房间。
 * 能力以 LangChain4j 原生方式书写：静态能力为 @Tool/@P/@ToolMemoryId 方法，
 * 记忆/知识库/技能为动态 ToolProvider；业务逻辑委托框架无关的共享服务，
 * 交付经 Matrix 媒体消息进房间。
 */
@Configuration
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
public class TeamWorkerConfig {

    private static final Logger log = LoggerFactory.getLogger(TeamWorkerConfig.class);

    private static final String AGENT_ID = "worker";

    @Bean
    public MatrixClient workerMatrixClient(
            @Value("${MATRIX_HOMESERVER_URL:http://localhost:8008}") String homeserver,
            @Value("${MATRIX_WORKER_USERNAME:worker}") String username,
            @Value("${MATRIX_WORKER_PASSWORD:}") String password) {
        MatrixClient client = new MatrixClient(homeserver);
        client.login(username, password);
        return client;
    }

    /** 团队专用模型：开启思考（budget 4096，maxTokens 必须更大）并记录 AG-UI 过程。 */
    @Bean
    public ChatModel teamWorkerChatModel(
            com.h.agent.observability.AgentObservability observability,
            @Value("${API_KEY:}") String apiKey,
            @Value("${MODEL_NAME:}") String modelName) {
        AnthropicChatModel delegate = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .baseUrl("https://api.minimaxi.com/anthropic/v1")
                .modelName(modelName)
                .maxTokens(16384)
                .thinkingType("enabled")
                .thinkingBudgetTokens(4096)
                .returnThinking(true)
                .timeout(java.time.Duration.ofSeconds(120))
                .build();
        return new com.h.otheragents.team.capability.RecordingChatModel(
                new com.h.agent.observability.langchain4j.ObservingChatModel(delegate, observability, "anthropic"));
    }

    @Bean
    public WorkerAssistant workerAssistant(
            @org.springframework.beans.factory.annotation.Qualifier("teamWorkerChatModel") ChatModel chatModel,
            MatrixClient workerMatrixClient,
            DocumentWorkflow documentWorkflow,
            ChatMemoryIdFactory memoryIds,
            ObjectMapper json,
            AssistantFileStorage fileStorage,
            ShellExecutionService shellExecutionService,
            WebSearchService webSearchService,
            FileDeliveryService fileDeliveryService,
            TeamImageGenerationTool images,
            TeamVideoGenerationTool videos,
            WorkerMemoryToolProvider memoryToolProvider,
            WorkerKnowledgeToolProvider knowledgeToolProvider,
            WorkerSkillToolProvider skillToolProvider,
            @Value("${MATRIX_TEAM_ROOM_ID:}") String roomId,
            @Value("${MATRIX_MANAGER_USERNAME:manager}") String managerUsername,
            @Value("${MATRIX_SPRING_WORKER_USERNAME:spring-worker}") String springWorkerUsername) {
        String domain = domainOf(workerMatrixClient.userId());
        String managerUserId = "@" + managerUsername + ":" + domain;
        String springWorkerUserId = "@" + springWorkerUsername + ":" + domain;
        return AiServices.builder(WorkerAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(1000))
                .tools(new TeamWorkerTools(workerMatrixClient, roomId, managerUserId,
                                workerMatrixClient.userId(), springWorkerUserId),
                        new WorkerDocumentTools(documentWorkflow, memoryIds, json),
                        new WorkerDeliveryTools(fileDeliveryService),
                        new WorkerFilesystemTools(fileStorage),
                        new WorkerShellTools(shellExecutionService),
                        new WorkerSearchTools(webSearchService),
                        new WorkerGenerationTools(images, videos, memoryIds))
                .toolProviders(List.of(memoryToolProvider, knowledgeToolProvider, skillToolProvider))
                .build();
    }

    @Bean
    public TeamRoomBridge.Handler workerHandler(
            WorkerAssistant workerAssistant,
            MatrixClient workerMatrixClient,
            SkillRuntimeService skillRuntimeService,
            @Value("${MATRIX_TEAM_ROOM_ID:}") String roomId,
            @Value("${MATRIX_MANAGER_USERNAME:manager}") String managerUsername,
            @Value("${MATRIX_HUMAN_USER_ID:17}") long humanUserId) {
        String managerUserId = "@" + managerUsername + ":" + domainOf(workerMatrixClient.userId());
        return message -> handle(workerAssistant, workerMatrixClient, skillRuntimeService,
                humanUserId, managerUserId, roomId, message);
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public TeamRoomBridge teamWorkerBridge(
            MatrixClient workerMatrixClient,
            @org.springframework.beans.factory.annotation.Qualifier("workerHandler")
            TeamRoomBridge.Handler workerHandler,
            com.h.matrixteam.TeamRunTracker teamRunTracker,
            @Value("${MATRIX_TEAM_ROOM_ID:}") String roomId,
            @Value("${MATRIX_MANAGER_USERNAME:manager}") String managerUsername,
            @Value("${MATRIX_SPRING_WORKER_USERNAME:spring-worker}") String springWorkerUsername,
            @Value("${MATRIX_MAX_AGENT_TURNS:12}") int maxAgentTurns) {
        String domain = domainOf(workerMatrixClient.userId());
        String managerUserId = "@" + managerUsername + ":" + domain;
        TeamPolicy policy = new TeamPolicy(TeamPolicy.Role.WORKER, workerMatrixClient.userId(),
                managerUserId, roomId, workerMatrixClient.userId(), "@" + springWorkerUsername + ":" + domain);
        return new TeamRoomBridge(workerMatrixClient, policy, List.of(roomId),
                workerHandler, maxAgentTurns, teamRunTracker);
    }

    private static void handle(WorkerAssistant assistant, MatrixClient client,
                               SkillRuntimeService skillRuntimeService, long humanUserId,
                               String managerUserId, String teamRoomId, TeamMessage message) {
        String memoryId = TeamIdentity.memoryId(humanUserId, AGENT_ID, message.roomId());
        String prompt = TeamProtocol.KIND_DELEGATE.equals(message.kind())
                ? "Manager 委派（任务 %s）：%s".formatted(message.taskId(), message.body())
                : message.roomId().equals(teamRoomId)
                        ? "来自 %s 的消息：%s".formatted(message.sender(), message.body())
                        : "Human 单聊消息：%s".formatted(message.body());
        try {
            skillRuntimeService.snapshotForTopLevelRun(humanUserId, null, memoryId);
            String skillsMessage = skillRuntimeService.skillsSystemMessage(memoryId);
            if (skillsMessage != null) {
                prompt = prompt + "\n\n" + skillsMessage;
            }
        } catch (RuntimeException failure) {
            log.warn("Worker 技能快照固定失败，本轮不带技能执行: {}", failure.getMessage());
        }
        InvocationParameters parameters = InvocationParameters.from(Map.of(
                AgentMemoryContext.KEY,
                TeamIdentity.agentContext(humanUserId, message.roomId(), AGENT_ID, message.eventId()),
                MemoryInvocationContext.INVOCATION_KEY,
                TeamIdentity.memoryContext(humanUserId, message.roomId(), AGENT_ID, message.eventId())));
        String reply;
        com.h.backend.capability.matrix.TeamActorContext.set(client.userId());
        com.h.backend.capability.matrix.TeamActorContext.setRoom(message.roomId());
        com.h.matrixteam.TeamProgressReporter reporter =
                new com.h.matrixteam.TeamProgressReporter(client, message.roomId());
        com.h.matrixteam.TeamProgressContext.set(reporter);
        reporter.start();
        try {
            reply = assistant.chat(memoryId, prompt, parameters);
            reporter.finish("COMPLETED");
        } catch (RuntimeException failure) {
            reporter.finish("FAILED");
            throw failure;
        } finally {
            com.h.matrixteam.TeamProgressContext.clear();
            com.h.backend.capability.matrix.TeamActorContext.clear();
        }
        if (reply == null || reply.isBlank() || "NO_REPLY".equals(reply.strip())) {
            return;
        }
        if (TeamProtocol.KIND_DELEGATE.equals(message.kind()) && message.taskId() != null) {
            client.sendText(message.roomId(), reply.strip(),
                    List.of(managerUserId), TeamProtocol.KIND_REPORT, message.taskId());
        } else {
            client.sendText(message.roomId(), reply.strip(), List.of(message.sender()), null, null);
        }
        log.info("Worker replied in room {} to event {}", message.roomId(), message.eventId());
    }

    private static String domainOf(String userId) {
        int colon = userId.indexOf(':');
        return colon < 0 ? "matrix.local" : userId.substring(colon + 1);
    }
}