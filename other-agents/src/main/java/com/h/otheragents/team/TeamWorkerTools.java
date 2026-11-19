package com.h.otheragents.team;

import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamProtocol;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.List;

/**
 * Worker 的协作工具：缺少信息时向 Manager 提问，而不是猜测或沉默。
 */
public class TeamWorkerTools {

    private final MatrixClient client;
    private final String roomId;
    private final String managerUserId;

    public TeamWorkerTools(MatrixClient client, String roomId, String managerUserId) {
        this.client = client;
        this.roomId = roomId;
        this.managerUserId = managerUserId;
    }

    @Tool("当任务缺少必要信息、无法继续时，向 Manager 提出一个具体问题。不要猜测缺失的需求。")
    public String askManager(@P("需要 Manager 澄清的具体问题") String question) {
        client.sendText(roomId, "[提问] " + question,
                List.of(managerUserId), TeamProtocol.KIND_QUESTION, null);
        return "已向 Manager 提问，等待澄清。当前可先输出已完成的部分或说明阻塞原因。";
    }
}