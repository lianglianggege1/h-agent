package com.h.backend.team;

import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamMessage;
import com.h.matrixteam.TeamProtocol;
import com.h.matrixteam.TeamRoomBridge;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;

/**
 * 把房间消息交给受限 Harness Manager 执行，最终回复发回同一房间。
 * 每个房间一个固定 Harness 会话，状态由 DistributedStore 持久化。
 */
public class TeamManagerHandler implements TeamRoomBridge.Handler {

    private static final Logger log = LoggerFactory.getLogger(TeamManagerHandler.class);

    private final HarnessAgent agent;
    private final MatrixClient client;
    private final String teamRoomId;

    public TeamManagerHandler(HarnessAgent agent, MatrixClient client, String teamRoomId) {
        this.agent = agent;
        this.client = client;
        this.teamRoomId = teamRoomId;
    }

    @Override
    public void handle(TeamMessage message) {
        boolean direct = !message.roomId().equals(teamRoomId);
        String prompt = switch (message.kind() == null ? "" : message.kind()) {
            case TeamProtocol.KIND_REPORT -> "Worker 回报（任务 %s）：%s".formatted(message.taskId(), message.body());
            case TeamProtocol.KIND_QUESTION -> "Worker 提问（任务 %s）：%s".formatted(message.taskId(), message.body());
            default -> direct
                    ? "Human 单聊消息：%s".formatted(message.body())
                    : "Human 消息（来自 %s）：%s".formatted(message.sender(), message.body());
        };
        RuntimeContext context = RuntimeContext.builder()
                .userId("team-manager")
                .sessionId("team-room-" + message.roomId())
                .build();
        Msg result = agent.call(prompt, context).block(Duration.ofMinutes(5));
        String text = result == null ? null : result.getTextContent();
        if (text == null || text.isBlank() || "NO_REPLY".equals(text.strip())) {
            return;
        }
        client.sendText(message.roomId(), text.strip(), List.of(), null, null);
        log.info("Manager replied in room {} to event {}", message.roomId(), message.eventId());
    }
}