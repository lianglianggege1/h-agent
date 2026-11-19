package com.h.backend.team;

import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamProtocol;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.List;
import java.util.UUID;

/**
 * Manager 的协作工具：委派必须走结构化消息，而不是正文里写一个名字。
 */
public class TeamManagerTools {

    private final MatrixClient client;
    private final String roomId;
    private final String workerUserId;

    public TeamManagerTools(MatrixClient client, String roomId, String workerUserId) {
        this.client = client;
        this.roomId = roomId;
        this.workerUserId = workerUserId;
    }

    @Tool(
            name = "delegate_to_worker",
            description = "把一项有明确交付要求的工作委派给 Worker Agent。Worker 完成后会向你在同一房间回报，"
                    + "届时你再检查并向 Human 汇总。普通问答不要使用本工具，直接回答即可。",
            concurrencySafe = true
    )
    public String delegate(
            @ToolParam(name = "task", description = "任务目标、必要背景与验收要求，写清楚 Worker 需要交付什么") String task) {
        String taskId = "task-" + UUID.randomUUID().toString().substring(0, 8);
        client.sendText(roomId, "[委派 " + taskId + "] " + task,
                List.of(workerUserId), TeamProtocol.KIND_DELEGATE, taskId);
        return "已委派 " + taskId + "，等待 Worker 回报。可以先向 Human 简短说明安排。";
    }
}