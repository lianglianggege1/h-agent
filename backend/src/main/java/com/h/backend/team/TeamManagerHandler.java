package com.h.backend.team;

import com.h.capabilities.seam.matrix.TeamActorContext;
import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamMessage;
import com.h.matrixteam.TeamProgressReporter;
import com.h.matrixteam.TeamProtocol;
import com.h.matrixteam.TeamRoomBridge;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.event.AgentEvent;
import io.agentscope.core.event.AgentResultEvent;
import io.agentscope.core.event.TextBlockDeltaEvent;
import io.agentscope.core.event.ThinkingBlockDeltaEvent;
import io.agentscope.core.event.ToolCallStartEvent;
import io.agentscope.core.event.ToolResultEndEvent;
import io.agentscope.core.event.ToolResultTextDeltaEvent;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.ToolResultState;
import io.agentscope.harness.agent.HarnessAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 把房间消息交给受限 Harness Manager 执行，最终回复发回同一房间。
 * 每个房间一个固定 Harness 会话，状态由 DistributedStore 持久化。
 * 执行过程（思考/工具步骤）以 com.h.agent.progress 快照事件持续上报，Matrix 历史即输出记录。
 */
public class TeamManagerHandler implements TeamRoomBridge.Handler {

    private static final Logger log = LoggerFactory.getLogger(TeamManagerHandler.class);

    private final HarnessAgent agent;
    private final MatrixClient client;
    private final String teamRoomId;
    private final long humanUserId;

    public TeamManagerHandler(HarnessAgent agent, MatrixClient client, String teamRoomId, long humanUserId) {
        this.agent = agent;
        this.client = client;
        this.teamRoomId = teamRoomId;
        this.humanUserId = humanUserId;
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
        // 能力数据归属人类工作台账号；sessionId 直接用 roomId（! 前缀），
        // 能力端口路由与记忆上下文解析据此识别团队 scope。
        RuntimeContext context = RuntimeContext.builder()
                .userId(String.valueOf(humanUserId))
                .sessionId(message.roomId())
                .build();
        TeamProgressReporter reporter = new TeamProgressReporter(client, message.roomId());
        AtomicReference<Msg> result = new AtomicReference<>();
        AtomicBoolean answerStarted = new AtomicBoolean();
        Map<String, StringBuilder> toolText = new ConcurrentHashMap<>();
        TeamActorContext.set(client.userId());
        TeamActorContext.setRoom(message.roomId());
        reporter.start();
        try {
            agent.streamEvents(prompt, context)
                    .timeout(Duration.ofMinutes(5))
                    .doOnNext(event -> project(event, reporter, result, answerStarted, toolText))
                    .blockLast();
            reporter.finish("COMPLETED");
        } catch (RuntimeException failure) {
            reporter.finish("FAILED");
            throw failure;
        } finally {
            TeamActorContext.clear();
        }
        Msg finalResult = result.get();
        String text = finalResult == null ? null : finalResult.getTextContent();
        if (text == null || text.isBlank() || "NO_REPLY".equals(text.strip())) {
            return;
        }
        client.sendText(message.roomId(), text.strip(), List.of(), null, null);
        log.info("Manager replied in room {} to event {}", message.roomId(), message.eventId());
    }

    private static void project(
            AgentEvent event,
            TeamProgressReporter reporter,
            AtomicReference<Msg> result,
            AtomicBoolean answerStarted,
            Map<String, StringBuilder> toolText) {
        if (event.getSource() != null && !event.getSource().isBlank()) {
            return;
        }
        if (event instanceof ThinkingBlockDeltaEvent thinking) {
            reporter.reasoning(thinking.getDelta());
        } else if (event instanceof ToolCallStartEvent tool) {
            reporter.toolStart(tool.getToolCallId(), tool.getToolCallName());
        } else if (event instanceof ToolResultTextDeltaEvent delta) {
            toolText.computeIfAbsent(delta.getToolCallId(), key -> new StringBuilder())
                    .append(delta.getDelta());
        } else if (event instanceof ToolResultEndEvent end) {
            StringBuilder text = toolText.remove(end.getToolCallId());
            ToolResultState state = end.getState();
            boolean failed = state == ToolResultState.ERROR
                    || state == ToolResultState.DENIED
                    || state == ToolResultState.INTERRUPTED;
            reporter.toolResult(end.getToolCallId(), text == null ? null : text.toString(), failed);
        } else if (event instanceof TextBlockDeltaEvent) {
            if (answerStarted.compareAndSet(false, true)) {
                reporter.answerStarted();
            }
        } else if (event instanceof AgentResultEvent agentResult) {
            result.set(agentResult.getResult());
        }
    }
}