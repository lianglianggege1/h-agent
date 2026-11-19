package com.h.otheragents.team;

import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamHistoryReader;
import com.h.matrixteam.TeamPolicy;
import com.h.matrixteam.TeamProtocol;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.List;

/**
 * Spring AI Worker 的协作工具：缺少信息时向 Manager 提问，而不是猜测或沉默；
 * 群聊历史按需翻阅，发言人带身份标签（含自己）。
 */
public class SpringWorkerTools {

    private final MatrixClient client;
    private final String roomId;
    private final String managerUserId;
    private final TeamHistoryReader history;

    public SpringWorkerTools(MatrixClient client, String roomId, String managerUserId, String... workerUserIds) {
        this.client = client;
        this.roomId = roomId;
        this.managerUserId = managerUserId;
        this.history = new TeamHistoryReader(client, roomId,
                new TeamPolicy(TeamPolicy.Role.WORKER, client.userId(), managerUserId, workerUserIds));
    }

    @Tool(description = "当任务缺少必要信息、无法继续时，向 Manager 提出一个具体问题。不要猜测缺失的需求。")
    public String askManager(@ToolParam(description = "需要 Manager 澄清的具体问题") String question) {
        client.sendText(roomId, "[提问] " + question,
                List.of(managerUserId), TeamProtocol.KIND_QUESTION, null);
        return "已向 Manager 提问，等待澄清。当前可先输出已完成的部分或说明阻塞原因。";
    }

    @Tool(description = "翻阅团队聊天室的历史消息，返回按时间正序、带发言人身份（Human/Manager/Worker/自己）的记录。"
            + "需要回顾之前的讨论、确认任务背景或查找更早上下文时自主调用；"
            + "默认返回最近 20 条，需要更早消息时把上次结果末尾的翻页令牌传入 before。"
            + "当前消息已足够回答时不要调用。")
    public String readRoomHistory(
            @ToolParam(required = false, description = "返回条数，1-50，缺省 20") Integer limit,
            @ToolParam(required = false, description = "更早历史的翻页令牌，来自上次调用结果末尾；缺省从最新开始") String before) {
        return history.read(limit, before);
    }
}