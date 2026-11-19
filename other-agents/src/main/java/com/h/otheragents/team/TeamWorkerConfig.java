package com.h.otheragents.team;

import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamMessage;
import com.h.matrixteam.TeamPolicy;
import com.h.matrixteam.TeamProtocol;
import com.h.matrixteam.TeamRoomBridge;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.service.AiServices;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 团队聊天室 Worker 装配：独立 Matrix 账号 + LangChain4j Agent。
 * 只在被 Manager 委派或被明确提及时执行，结果以结构化回报发回房间。
 */
@Configuration
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
public class TeamWorkerConfig {

    private static final Logger log = LoggerFactory.getLogger(TeamWorkerConfig.class);

    @Bean
    public MatrixClient workerMatrixClient(
            @Value("${MATRIX_HOMESERVER_URL:http://localhost:8008}") String homeserver,
            @Value("${MATRIX_WORKER_USERNAME:worker}") String username,
            @Value("${MATRIX_WORKER_PASSWORD:}") String password) {
        MatrixClient client = new MatrixClient(homeserver);
        client.login(username, password);
        return client;
    }

    @Bean
    public WorkerAssistant workerAssistant(
            ChatModel chatModel,
            MatrixClient workerMatrixClient,
            @Value("${MATRIX_TEAM_ROOM_ID:}") String roomId,
            @Value("${MATRIX_MANAGER_USERNAME:manager}") String managerUsername) {
        String managerUserId = "@" + managerUsername + ":" + domainOf(workerMatrixClient.userId());
        return AiServices.builder(WorkerAssistant.class)
                .chatModel(chatModel)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.withMaxMessages(30))
                .tools(new TeamWorkerTools(workerMatrixClient, roomId, managerUserId))
                .build();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public TeamRoomBridge teamWorkerBridge(
            MatrixClient workerMatrixClient,
            WorkerAssistant workerAssistant,
            @Value("${MATRIX_TEAM_ROOM_ID:}") String roomId,
            @Value("${MATRIX_MANAGER_USERNAME:manager}") String managerUsername,
            @Value("${MATRIX_MAX_AGENT_TURNS:12}") int maxAgentTurns) {
        String managerUserId = "@" + managerUsername + ":" + domainOf(workerMatrixClient.userId());
        TeamPolicy policy = new TeamPolicy(TeamPolicy.Role.WORKER, workerMatrixClient.userId(), managerUserId);
        return new TeamRoomBridge(workerMatrixClient, policy, List.of(roomId),
                message -> handle(workerAssistant, workerMatrixClient, managerUserId, message), maxAgentTurns);
    }

    private static void handle(WorkerAssistant assistant, MatrixClient client,
                               String managerUserId, TeamMessage message) {
        String prompt = TeamProtocol.KIND_DELEGATE.equals(message.kind())
                ? "Manager 委派（任务 %s）：%s".formatted(message.taskId(), message.body())
                : "来自 %s 的消息：%s".formatted(message.sender(), message.body());
        String reply = assistant.chat(message.roomId(), prompt);
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