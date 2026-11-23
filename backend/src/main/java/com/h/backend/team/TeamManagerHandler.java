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
    private final com.h.capabilities.seam.matrix.TeamHumanDirectory directory;

    public TeamManagerHandler(HarnessAgent agent, MatrixClient client,
                              com.h.capabilities.seam.matrix.TeamHumanDirectory directory) {
        this.agent = agent;
        this.client = client;
        this.directory = directory;
    }

    @Override
    public void handle(TeamMessage message) {
        boolean direct = !directory.isTeamRoom(message.roomId());
        boolean fromHuman = directory.isHuman(message.sender());
        Long owner = directory.resolveOwner(message.sender(), message.originUserId());
        if (owner == null) {
            log.warn("Manager 跳过无法归属的消息 event={} sender={}", message.eventId(), message.sender());
            return;
        }
        String prompt = switch (message.kind() == null ? "" : message.kind()) {
            case TeamProtocol.KIND_REPORT -> "Worker 回报（任务 %s）：%s".formatted(message.taskId(), message.body());
            case TeamProtocol.KIND_QUESTION -> "Worker 提问（任务 %s）：%s".formatted(message.taskId(), message.body());
            default -> {
                if (!direct) {
                    yield "Human 消息（来自 %s）：%s".formatted(message.sender(), message.body());
                }
                yield fromHuman
                        ? "Human 单聊消息：%s".formatted(message.body())
                        : "成员单聊消息（来自 %s）：%s".formatted(message.sender(), message.body());
            }
        };
        // 能力数据归属真实登录用户（按事件 sender / 委派链解析）；sessionId 直接用 roomId（! 前缀），
        // 能力端口路由与记忆上下文解析据此识别团队 scope。
        RuntimeContext context = RuntimeContext.builder()
                .userId(String.valueOf(owner))
                .sessionId(message.roomId())
                .build();
        TeamProgressReporter reporter = new TeamProgressReporter(client, message.roomId());
        AtomicReference<Msg> result = new AtomicReference<>();
        AtomicBoolean answerStarted = new AtomicBoolean();
        Map<String, StringBuilder> toolText = new ConcurrentHashMap<>();
        TeamActorContext.set(client.userId());
        TeamActorContext.setRoom(message.roomId());
        TeamActorContext.setOrigin(String.valueOf(owner));
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
        // 成员单聊时点名对方，保证对方的单聊触发策略能收到回应；人类单聊无需点名。
        List<String> mentions = direct && !fromHuman ? List.of(message.sender()) : List.of();
        client.sendText(message.roomId(), text.strip(), mentions, null, null, String.valueOf(owner));
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