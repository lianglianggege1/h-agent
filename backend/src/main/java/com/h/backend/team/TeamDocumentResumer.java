package com.h.backend.team;

import com.h.backend.capability.matrix.TeamDocumentContinuation;
import com.h.backend.document.domain.DocumentTask;
import com.h.matrixteam.TeamMessage;

import java.util.List;

/** 团队 scope 的文档续跑入口：以人类身份沿房间锚点重新调用 Manager，结果发回原房间。 */
public class TeamDocumentResumer {

    private final TeamDocumentContinuation continuation;
    private final TeamManagerHandler handler;
    private final String humanMatrixUserId;

    public TeamDocumentResumer(
            TeamDocumentContinuation continuation, TeamManagerHandler handler, String humanMatrixUserId) {
        this.continuation = continuation;
        this.handler = handler;
        this.humanMatrixUserId = humanMatrixUserId;
    }

    public void resume(DocumentTask task) {
        continuation.resume(task, prompt -> handler.handle(new TeamMessage(
                "doc-continuation:" + task.getId(), task.getSessionId(), humanMatrixUserId,
                prompt, List.of(), false, null, null)));
    }
}