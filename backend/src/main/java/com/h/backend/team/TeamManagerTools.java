package com.h.backend.team;

import com.h.capabilities.seam.matrix.TeamRoomAuthorizer;
import com.h.matrixteam.MatrixClient;
import com.h.matrixteam.TeamCollaboration;
import com.h.matrixteam.TeamPolicy;
import com.h.matrixteam.TeamProtocol;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Stream;

/**
 * Manager 的协作工具：委派必须走结构化消息并点名目标 Worker，而不是正文里写一个名字；
 * 群聊历史按需翻阅，发言人带身份标签（含自己）；
 * 社交互通：感知所在房间、跨房间读历史、给成员发消息（群内点名或单聊）、跨房间分享。
 * 当前房间取自 RuntimeContext.sessionId（工具异步执行，不依赖 ThreadLocal）。
 */
public class TeamManagerTools {

    private final MatrixClient client;
    private final Map<String, String> workerUserIds;
    private final TeamCollaboration collaboration;
    private final TeamRoomAuthorizer authorizer;

    public TeamManagerTools(MatrixClient client, Map<String, String> workerUserIds,
                            TeamRoomAuthorizer authorizer,
                            com.h.capabilities.seam.matrix.TeamHumanDirectory directory) {
        this.client = client;
        this.workerUserIds = Map.copyOf(workerUserIds);
        this.authorizer = authorizer;
        TeamPolicy policy = new TeamPolicy(TeamPolicy.Role.MANAGER, client.userId(), client.userId(),
                directory::isTeamRoom, workerUserIds.values().toArray(String[]::new));
        this.collaboration = new TeamCollaboration(client, policy,
                TeamCollaboration.rosterOf(Stream.concat(Stream.of(client.userId()), workerUserIds.values().stream())
                        .toArray(String[]::new)));
    }

    private String currentRoom(RuntimeContext context) {
        return context == null ? null : context.getSessionId();
    }

    /** 本轮归属用户的房间访问谓词：当前房间恒允许，跨房间按归属用户成员身份判定。 */
    private Predicate<String> scope(RuntimeContext context) {
        String current = currentRoom(context);
        Long origin = originLong(context);
        return target -> target != null
                && (target.equals(current) || (origin != null && authorizer.canAccess(origin, target)));
    }

    private static Long originLong(RuntimeContext context) {
        String origin = originOf(context);
        if (origin == null || origin.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(origin.strip());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Tool(
            name = "delegate_to_worker",
            description = "把一项有明确交付要求的工作委派给指定 Worker Agent（worker 为 LangChain4j 实现，spring-worker 为 Spring AI 实现）。"
                    + "Worker 完成后会向你在同一房间回报，届时你再检查并向 Human 汇总。普通问答不要使用本工具，直接回答即可。",
            concurrencySafe = true
    )
    public String delegate(
            RuntimeContext context,
            @ToolParam(name = "task", description = "任务目标、必要背景与验收要求，写清楚 Worker 需要交付什么") String task,
            @ToolParam(name = "worker", required = false, description = "指派给哪位 Worker：worker 或 spring-worker，缺省 worker") String worker) {
        String name = worker == null || worker.isBlank() ? "worker" : worker.strip();
        String workerUserId = workerUserIds.get(name);
        if (workerUserId == null) {
            return "未知 Worker：" + name + "，可选 " + String.join(" / ", workerUserIds.keySet());
        }
        String taskId = "task-" + UUID.randomUUID().toString().substring(0, 8);
        String room = currentRoom(context);
        if (room == null || room.isBlank()) {
            return "无法确定当前房间，未委派。";
        }
        client.sendText(room, "[委派 " + taskId + "] " + task,
                List.of(workerUserId), TeamProtocol.KIND_DELEGATE, taskId, originOf(context));
        return "已委派 " + taskId + " 给 " + name + "，等待 Worker 回报。可以先向 Human 简短说明安排。";
    }

    private static String originOf(RuntimeContext context) {
        if (context != null && context.getUserId() != null && !context.getUserId().isBlank()) {
            return context.getUserId();
        }
        return com.h.capabilities.seam.matrix.TeamActorContext.getOrigin();
    }

    @Tool(
            name = "read_room_history",
            description = "翻阅聊天室的历史消息，返回按时间正序、带发言人身份（Human/Manager/Worker/自己）的记录。"
                    + "缺省读当前所在房间（群聊或单聊）；传 roomId 可读其他已加入房间的历史。"
                    + "需要回顾之前的讨论、确认任务背景或查找更早上下文时自主调用；"
                    + "默认返回最近 20 条，需要更早消息时把上次结果末尾的翻页令牌传入 before。"
                    + "当前消息已足够回答时不要调用。",
            concurrencySafe = true
    )
    public String readHistory(
            RuntimeContext context,
            @ToolParam(name = "limit", required = false, description = "返回条数，1-50，缺省 20") Integer limit,
            @ToolParam(name = "before", required = false, description = "更早历史的翻页令牌，来自上次调用结果末尾；缺省从最新开始") String before,
            @ToolParam(name = "roomId", required = false, description = "目标房间 ID（list_my_rooms 可查）；缺省当前房间") String roomId) {
        String target = roomId == null || roomId.isBlank() ? currentRoom(context) : roomId;
        return collaboration.readRoom(target, limit, before, scope(context));
    }

    @Tool(
            name = "list_my_rooms",
            description = "列出我加入的所有房间（群聊/单聊）、房间名称、成员与角色，并标记当前所在房间。"
                    + "想知道自己身在哪个群/单聊、还有哪些房间和成员可互通时调用。",
            concurrencySafe = true
    )
    public String listMyRooms(RuntimeContext context) {
        return collaboration.listRooms(currentRoom(context), scope(context));
    }

    @Tool(
            name = "message_member",
            description = "给团队另一位成员发消息，互通有无。target 用名字（worker/spring-worker，或 Human 的 Matrix 用户名）或完整 userId。"
                    + "direct=true 时与对方建立/复用单聊私聊；缺省在当前房间（或指定 roomId 的房间）内点名对方。"
                    + "是否发、发给谁、说什么由你判断；对方收到后会自行回应。正式任务委派仍用 delegate_to_worker。",
            concurrencySafe = false
    )
    public String messageMember(
            RuntimeContext context,
            @ToolParam(name = "target", description = "目标成员名字或 userId") String target,
            @ToolParam(name = "text", description = "消息正文") String text,
            @ToolParam(name = "direct", required = false, description = "true=与对方单聊；缺省 false=在房间内点名") Boolean direct,
            @ToolParam(name = "roomId", required = false, description = "群内发送时的目标房间 ID；缺省当前房间") String roomId) {
        return collaboration.messageMember(currentRoom(context), target, text, roomId, Boolean.TRUE.equals(direct),
                originOf(context), scope(context));
    }

    @Tool(
            name = "share_to_room",
            description = "把文本或媒体成果分享到另一个房间（跨房间互通有无）。"
                    + "roomId 必填（list_my_rooms 可查）；分享媒体传 mxcUrl（mxc:// 地址，如生成结果的地址），"
                    + "分享文本传 text。发到哪个房间由你判断。",
            concurrencySafe = false
    )
    public String shareToRoom(
            RuntimeContext context,
            @ToolParam(name = "roomId", description = "目标房间 ID") String roomId,
            @ToolParam(name = "text", required = false, description = "分享的文本内容") String text,
            @ToolParam(name = "mxcUrl", required = false, description = "分享的媒体 mxc:// 地址") String mxcUrl,
            @ToolParam(name = "filename", required = false, description = "媒体文件名（用于判断消息类型与展示）") String filename) {
        return collaboration.shareToRoom(roomId, text, mxcUrl, filename, null, originOf(context), scope(context));
    }
}