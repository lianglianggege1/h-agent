package com.h.backend.chat.application;

import com.h.backend.chat.interfaces.dto.HarnessSubagentSummaryDto;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceUseDto;

import java.util.List;

public interface HarnessCollaborationService {

    List<HarnessSubagentSummaryDto> listSubagents(Long userId, String parentSessionId);

    /** 仅凭实际 sessionId 解析顶级归属和内部 Gateway 句柄。 */
    HarnessExecutionSession resolveExecutionSession(Long userId, String sessionId);

    /**
     * 子 Agent 自身完成边界触发的完整产品投影，保留本轮思考并确保它位于最终回复之前。
     * 重复父流事件必须幂等。
     */
    void projectSubagentResult(
            Long userId,
            String sessionId,
            String assignment,
            String reasoning,
            String content
    );

    /** 子 Agent 自身开始边界触发的委托投影；只允许补全首轮 SYSTEM 委托。 */
    void projectSubagentAssignment(Long userId, String sessionId, String assignment);

    HarnessSubagentSummaryDto exposeSubagent(
            Long userId,
            String parentSessionId,
            HarnessSubagentExposure exposure
    );

    HarnessSubagentSummaryDto markRunning(
            Long userId,
            String parentSessionId,
            String sessionId,
            String executionId
    );

    HarnessSubagentCompletion completeSubagent(
            Long userId,
            String parentSessionId,
            String sessionId,
            String executionId,
            String content
    );

    HarnessSubagentTurnStart beginSubagentTurn(
            Long userId,
            String parentSessionId,
            String sessionId,
            String content,
            List<ChatMessageResourceUseDto> resources
    );

    HarnessSubagentSummaryDto failSubagent(
            Long userId,
            String parentSessionId,
            String sessionId,
            String executionId,
            HarnessSubagentFailureReason reason,
            String message
    );

}
