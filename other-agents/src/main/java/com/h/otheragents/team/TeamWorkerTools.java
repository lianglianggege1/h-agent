package com.h.otheragents.team;

import com.h.capabilities.seam.matrix.TeamActorContext;
import com.h.capabilities.seam.matrix.TeamRoomAuthorizer;
import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamCollaboration;
import com.h.matrixteam.TeamPolicy;
import com.h.matrixteam.TeamProtocol;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Worker 的协作工具：缺少信息时向 Manager 提问，而不是猜测或沉默；
 * 群聊历史按需翻阅，发言人带身份标签（含自己）；
 * 社交互通：感知所在房间、跨房间读历史、给成员发消息（群内点名或单聊）、跨房间分享。
 */
public class TeamWorkerTools {

    private final MatrixClient client;
    private final String roomId;
    private final String managerUserId;
    private final TeamCollaboration collaboration;
    private final TeamRoomAuthorizer authorizer;

    public TeamWorkerTools(MatrixClient client, String roomId, String managerUserId, TeamRoomAuthorizer authorizer,
                           String... workerUserIds) {
        this.client = client;
        this.roomId = roomId;
        this.managerUserId = managerUserId;
        this.authorizer = authorizer;
        TeamPolicy policy = new TeamPolicy(TeamPolicy.Role.WORKER, client.userId(), managerUserId, roomId, workerUserIds);
        this.collaboration = new TeamCollaboration(client, policy, roomId,
                TeamCollaboration.rosterOf(Stream.concat(Stream.of(managerUserId), Arrays.stream(workerUserIds))
                        .toArray(String[]::new)));
    }

    private String currentRoom() {
        String room = TeamActorContext.getRoom();
        return room == null || room.isBlank() ? roomId : room;
    }

    /** 本轮归属用户的房间访问谓词：当前房间恒允许，跨房间按归属用户成员身份判定。 */
    private Predicate<String> scope() {
        String current = currentRoom();
        Long origin = originLong();
        return target -> target != null
                && (target.equals(current) || (origin != null && authorizer.canAccess(origin, target)));
    }

    private static Long originLong() {
        String origin = TeamActorContext.getOrigin();
        if (origin == null || origin.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(origin.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Tool("当任务缺少必要信息、无法继续时，向 Manager 提出一个具体问题。不要猜测缺失的需求。")
    public String askManager(@P("需要 Manager 澄清的具体问题") String question) {
        client.sendText(currentRoom(), "[提问] " + question,
                List.of(managerUserId), TeamProtocol.KIND_QUESTION, null, TeamActorContext.getOrigin());
        return "已向 Manager 提问，等待澄清。当前可先输出已完成的部分或说明阻塞原因。";
    }

    @Tool("翻阅聊天室的历史消息，返回按时间正序、带发言人身份（Human/Manager/Worker/自己）的记录。"
            + "缺省读当前所在房间（群聊或单聊）；传 roomId 可读其他已加入房间的历史。"
            + "需要回顾之前的讨论、确认任务背景或查找更早上下文时自主调用；"
            + "默认返回最近 20 条，需要更早消息时把上次结果末尾的翻页令牌传入 before。"
            + "当前消息已足够回答时不要调用。")
    public String readRoomHistory(
            @P(value = "返回条数，1-50，缺省 20", required = false) Integer limit,
            @P(value = "更早历史的翻页令牌，来自上次调用结果末尾；缺省从最新开始", required = false) String before,
            @P(value = "目标房间 ID（listMyRooms 可查）；缺省当前房间", required = false) String roomId) {
        String target = roomId == null || roomId.isBlank() ? currentRoom() : roomId;
        return collaboration.readRoom(target, limit, before, scope());
    }

    @Tool("列出我加入的所有房间（群聊/单聊）、房间名称、成员与角色，并标记当前所在房间。"
            + "想知道自己身在哪个群/单聊、还有哪些房间和成员可互通时调用。")
    public String listMyRooms() {
        return collaboration.listRooms(currentRoom(), scope());
    }

    @Tool("给团队另一位成员发消息，互通有无。target 用名字（manager/worker/spring-worker，或 Human 的 Matrix 用户名）或完整 userId。"
            + "direct=true 时与对方建立/复用单聊私聊；缺省在当前房间（或指定 roomId 的房间）内点名对方。"
            + "是否发、发给谁、说什么由你判断；对方收到后会自行回应。")
    public String messageMember(
            @P("目标成员名字或 userId") String target,
            @P("消息正文") String text,
            @P(value = "true=与对方单聊；缺省 false=在房间内点名", required = false) Boolean direct,
            @P(value = "群内发送时的目标房间 ID；缺省当前房间", required = false) String roomId) {
        return collaboration.messageMember(currentRoom(), target, text, roomId, Boolean.TRUE.equals(direct),
                TeamActorContext.getOrigin(), scope());
    }

    @Tool("把文本或媒体成果分享到另一个房间（跨房间互通有无）。"
            + "roomId 必填（listMyRooms 可查）；分享媒体传 mxcUrl（mxc:// 地址，如生成结果的地址），"
            + "分享文本传 text。发到哪个房间由你判断。")
    public String shareToRoom(
            @P("目标房间 ID") String roomId,
            @P(value = "分享的文本内容", required = false) String text,
            @P(value = "分享的媒体 mxc:// 地址", required = false) String mxcUrl,
            @P(value = "媒体文件名（用于判断消息类型与展示）", required = false) String filename) {
        return collaboration.shareToRoom(roomId, text, mxcUrl, filename, null, TeamActorContext.getOrigin(), scope());
    }
}