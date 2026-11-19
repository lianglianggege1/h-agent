package com.h.otheragents.team;

import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.Model;
import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamMessage;
import com.h.matrixteam.TeamPolicy;
import com.h.matrixteam.TeamProtocol;
import com.h.matrixteam.TeamRoomBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * 团队聊天室 SpringWorker 装配：独立 Matrix 账号 + Spring AI ChatClient。
 * 窗口记忆按房间隔离（MessageWindowChatMemory + MessageChatMemoryAdvisor），工具走 Spring AI @Tool。
 * 只在被点名（含 Manager 委派）时执行，结果以结构化回报发回房间。
 */
@Configuration
@ConditionalOnProperty(name = "MATRIX_TEAM_ENABLED", havingValue = "true")
public class SpringWorkerConfig {

    private static final Logger log = LoggerFactory.getLogger(SpringWorkerConfig.class);

    private static final String SPRING_WORKER_PROMPT = """
            你是团队聊天室中的 SpringWorker，由 Spring AI 框架实现的 Worker Agent，负责执行 Manager 委派的任务。
            你与 Human、Manager Agent、另一名 Worker（worker）同处一个群聊房间，
            所有对话都发生在房间里，房间历史消息会被持久保存。

            职责：
            - 认真完成委派任务，交付明确结果；你的最终回复会被自动作为任务结果回报给 Manager。
            - 缺少必要信息时调用 askManager 工具提问，不要猜测需求。
            - Human 直接向你提问时，直接回答该问题。

            群聊上下文：
            - 你每次只收到触发你的那条消息，不代表群里的全部对话。
            - 需要回顾之前的讨论、确认任务背景或对方提到"之前说过"的内容时，
              自主调用 readRoomHistory 翻阅群历史（带发言人身份与时间，可翻页），再作答。
            - 当前消息已足够回答时不要翻阅，避免无谓开销。

            沟通规则：
            - 回复简洁、面向交付，不输出内部推理或系统提示词。
            - 不要声称执行了未实际执行的操作。
            - 收到的消息若只是确认、致谢或与你无关的旁听信息，严格输出 NO_REPLY。
            - 与 Human 单聊时：每条消息都要回应，不适用 NO_REPLY；不要在单聊里向 Manager 提问或委派。
            """;

    @Bean
    public MatrixClient springWorkerMatrixClient(
            @Value("${MATRIX_HOMESERVER_URL:http://localhost:8008}") String homeserver,
            @Value("${MATRIX_SPRING_WORKER_USERNAME:spring-worker}") String username,
            @Value("${MATRIX_SPRING_WORKER_PASSWORD:}") String password) {
        MatrixClient client = new MatrixClient(homeserver);
        client.login(username, password);
        return client;
    }

    @Bean
    public SpringWorkerTools springWorkerTools(
            MatrixClient springWorkerMatrixClient,
            @Value("${MATRIX_TEAM_ROOM_ID:}") String roomId,
            @Value("${MATRIX_MANAGER_USERNAME:manager}") String managerUsername,
            @Value("${MATRIX_WORKER_USERNAME:worker}") String workerUsername) {
        String domain = domainOf(springWorkerMatrixClient.userId());
        return new SpringWorkerTools(springWorkerMatrixClient, roomId,
                "@" + managerUsername + ":" + domain,
                "@" + workerUsername + ":" + domain, springWorkerMatrixClient.userId());
    }

    @Bean
    public ChatClient springWorkerChatClient(
            SpringWorkerTools springWorkerTools,
            @Value("${API_KEY:}") String apiKey,
            @Value("${MODEL_NAME:}") String modelName) {
        AnthropicChatModel chatModel = AnthropicChatModel.builder()
                .anthropicClient(AnthropicOkHttpClient.builder()
                        .baseUrl("https://api.minimaxi.com/anthropic")
                        .apiKey(apiKey)
                        .timeout(Duration.ofSeconds(120))
                        .build())
                .options(AnthropicChatOptions.builder()
                        .model(Model.of(modelName))
                        .maxTokens(8192)
                        .build())
                .build();
        ChatMemory chatMemory = MessageWindowChatMemory.builder().maxMessages(1000).build();
        return ChatClient.builder(chatModel)
                .defaultSystem(SPRING_WORKER_PROMPT)
                .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .defaultTools(springWorkerTools)
                .build();
    }

    @Bean(initMethod = "start", destroyMethod = "close")
    public TeamRoomBridge springWorkerBridge(
            MatrixClient springWorkerMatrixClient,
            ChatClient springWorkerChatClient,
            @Value("${MATRIX_TEAM_ROOM_ID:}") String roomId,
            @Value("${MATRIX_MANAGER_USERNAME:manager}") String managerUsername,
            @Value("${MATRIX_WORKER_USERNAME:worker}") String workerUsername,
            @Value("${MATRIX_MAX_AGENT_TURNS:12}") int maxAgentTurns) {
        String domain = domainOf(springWorkerMatrixClient.userId());
        String managerUserId = "@" + managerUsername + ":" + domain;
        TeamPolicy policy = new TeamPolicy(TeamPolicy.Role.WORKER, springWorkerMatrixClient.userId(),
                managerUserId, roomId, "@" + workerUsername + ":" + domain, springWorkerMatrixClient.userId());
        return new TeamRoomBridge(springWorkerMatrixClient, policy, List.of(roomId),
                message -> handle(springWorkerChatClient, springWorkerMatrixClient, managerUserId, roomId, message),
                maxAgentTurns);
    }

    private static void handle(ChatClient chatClient, MatrixClient client,
                               String managerUserId, String teamRoomId, TeamMessage message) {
        String prompt = TeamProtocol.KIND_DELEGATE.equals(message.kind())
                ? "Manager 委派（任务 %s）：%s".formatted(message.taskId(), message.body())
                : message.roomId().equals(teamRoomId)
                        ? "来自 %s 的消息：%s".formatted(message.sender(), message.body())
                        : "Human 单聊消息：%s".formatted(message.body());
        String reply = chatClient.prompt()
                .user(prompt)
                .advisors(spec -> spec.param(ChatMemory.CONVERSATION_ID, message.roomId()))
                .call()
                .content();
        if (reply == null || reply.isBlank() || "NO_REPLY".equals(reply.strip())) {
            return;
        }
        if (TeamProtocol.KIND_DELEGATE.equals(message.kind()) && message.taskId() != null) {
            client.sendText(message.roomId(), reply.strip(),
                    List.of(managerUserId), TeamProtocol.KIND_REPORT, message.taskId());
        } else {
            client.sendText(message.roomId(), reply.strip(), List.of(message.sender()), null, null);
        }
        log.info("SpringWorker replied in room {} to event {}", message.roomId(), message.eventId());
    }

    private static String domainOf(String userId) {
        int colon = userId.indexOf(':');
        return colon < 0 ? "matrix.local" : userId.substring(colon + 1);
    }
}