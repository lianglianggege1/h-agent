package com.h.backend.team;

import com.h.capabilities.seam.matrix.TeamDocumentContinuation;
import com.h.capabilities.document.domain.DocumentTask;
import com.h.matrixteam.TeamMessage;

import java.util.List;

/**
 * 团队 scope 的文档续跑入口：沿房间锚点重新调用 Manager，结果发回原房间。
 * 归属按任务创建时记录的工作台用户 id（originUserId）解析，sender 用创建任务的 Agent 身份。
 */
public class TeamDocumentResumer {

    private final TeamDocumentContinuation continuation;
    private final TeamManagerHandler handler;

    public TeamDocumentResumer(TeamDocumentContinuation continuation, TeamManagerHandler handler) {
        this.continuation = continuation;
        this.handler = handler;
    }

    public void resume(DocumentTask task) {
        continuation.resume(task, prompt -> handler.handle(new TeamMessage(
                "doc-continuation:" + task.getId(), task.getSessionId(), task.getActor(),
                prompt, List.of(), false, null, null, "m.text", null, null, 0,
                String.valueOf(task.getUserId()))));
    }
}