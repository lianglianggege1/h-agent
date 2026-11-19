package com.h.backend.team;

import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamHistoryReader;
import com.h.matrixteam.TeamPolicy;
import com.h.matrixteam.TeamProtocol;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manager 的协作工具：委派必须走结构化消息并点名目标 Worker，而不是正文里写一个名字；
 * 群聊历史按需翻阅，发言人带身份标签（含自己）。
 */
public class TeamManagerTools {

    private final MatrixClient client;
    private final String roomId;
    private final Map<String, String> workerUserIds;
    private final TeamHistoryReader history;

    public TeamManagerTools(MatrixClient client, String roomId, Map<String, String> workerUserIds) {
        this.client = client;
        this.roomId = roomId;
        this.workerUserIds = Map.copyOf(workerUserIds);
        this.history = new TeamHistoryReader(client, roomId,
                new TeamPolicy(TeamPolicy.Role.MANAGER, client.userId(), client.userId(), roomId,
                        workerUserIds.values().toArray(String[]::new)));
    }

    @Tool(
            name = "delegate_to_worker",
            description = "把一项有明确交付要求的工作委派给指定 Worker Agent（worker 为 LangChain4j 实现，spring-worker 为 Spring AI 实现）。"
                    + "Worker 完成后会向你在同一房间回报，届时你再检查并向 Human 汇总。普通问答不要使用本工具，直接回答即可。",
            concurrencySafe = true
    )
    public String delegate(
            @ToolParam(name = "task", description = "任务目标、必要背景与验收要求，写清楚 Worker 需要交付什么") String task,
            @ToolParam(name = "worker", required = false, description = "指派给哪位 Worker：worker 或 spring-worker，缺省 worker") String worker) {
        String name = worker == null || worker.isBlank() ? "worker" : worker.strip();
        String workerUserId = workerUserIds.get(name);
        if (workerUserId == null) {
            return "未知 Worker：" + name + "，可选 " + String.join(" / ", workerUserIds.keySet());
        }
        String taskId = "task-" + UUID.randomUUID().toString().substring(0, 8);
        client.sendText(roomId, "[委派 " + taskId + "] " + task,
                List.of(workerUserId), TeamProtocol.KIND_DELEGATE, taskId);
        return "已委派 " + taskId + " 给 " + name + "，等待 Worker 回报。可以先向 Human 简短说明安排。";
    }

    @Tool(
            name = "read_room_history",
            description = "翻阅团队聊天室的历史消息，返回按时间正序、带发言人身份（Human/Manager/Worker/自己）的记录。"
                    + "需要回顾之前的讨论、确认任务背景或查找更早上下文时自主调用；"
                    + "默认返回最近 20 条，需要更早消息时把上次结果末尾的翻页令牌传入 before。"
                    + "当前消息已足够回答时不要调用。",
            concurrencySafe = true
    )
    public String readHistory(
            @ToolParam(name = "limit", required = false, description = "返回条数，1-50，缺省 20") Integer limit,
            @ToolParam(name = "before", required = false, description = "更早历史的翻页令牌，来自上次调用结果末尾；缺省从最新开始") String before) {
        return history.read(limit, before);
    }
}