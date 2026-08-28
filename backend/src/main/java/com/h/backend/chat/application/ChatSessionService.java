package com.h.backend.chat.application;

import com.h.backend.chat.interfaces.dto.ChatSessionBootstrapDto;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceUseDto;
import com.h.backend.chat.interfaces.dto.ChatMessageResourceDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessageDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMessagesPageDto;
import com.h.backend.chat.interfaces.dto.ChatSessionMetaDto;
import com.h.backend.chat.interfaces.dto.ChatSessionOpenDto;
import com.h.backend.chat.interfaces.dto.ChatSessionSummaryDto;
import com.h.backend.chat.domain.model.ChatMessagePayload;
import com.h.backend.chat.infrastructure.storage.StoredResource;
import com.h.backend.chat.domain.approval.ApprovalMode;

import java.util.List;
import java.util.Map;

public interface ChatSessionService {

    ChatSessionBootstrapDto bootstrap(Long userId);

    ChatSessionOpenDto createSession(
            Long userId,
            Long promptId,
            String agentId,
            ApprovalMode approvalMode,
            String currentSessionId
    );

    default ChatSessionOpenDto createSession(
            Long userId,
            Long promptId,
            String agentId,
            String currentSessionId
    ) {
        return createSession(userId, promptId, agentId, null, currentSessionId);
    }

    ChatSessionOpenDto chooseActiveSession(Long userId, String selectedSessionId);

    ChatSessionOpenDto activateHistorySession(Long userId, String targetSessionId, String currentSessionId);

    ChatSessionMetaDto getSessionDetail(Long userId, String sessionId);

    ChatSessionMessagesPageDto getSessionMessages(Long userId, String sessionId, int limit, Integer beforeSeq);

    ChatSessionMessageDto getOwnedMessage(Long userId, String sessionId, Long messageId);

    List<ChatSessionSummaryDto> listHistory(Long userId, int page, int size);

    void archiveExpiredSessions();

    void assertActiveSession(Long userId, String sessionId, Long promptId, String agentId);

    void assertActiveAgentSession(Long userId, String sessionId, String agentId);

    Long appendUserMessage(Long userId, String sessionId, String userMessage, List<ChatMessageResourceUseDto> resources);

    Long appendBlockedMessage(Long userId, String sessionId, String blockedMessage);

    Long appendReasoningMessage(Long userId, String sessionId, String reasoningMessage);

    Long appendAssistantMessage(Long userId, String sessionId, String assistantMessage);

    Long appendAssistantMessage(Long userId, String sessionId, String assistantMessage, List<ChatMessageResourceUseDto> resources);

    ChatMessageResourceDto bindStoredAudioResource(
            Long userId,
            String sessionId,
            Long messageId,
            String source,
            StoredResource storedResource,
            Map<String, Object> metadata
    );

    ChatSessionMessageDto appendImageMessage(
            Long userId,
            String sessionId,
            String imagePrompt,
            ChatMessagePayload payload,
            List<ChatMessageResourceDto> resources
    );

    ChatSessionMessageDto appendResourceMessage(
            Long userId,
            String sessionId,
            String content,
            String messageType,
            List<ChatMessageResourceDto> resources
    );

    ChatSessionMessageDto appendGeneratedMediaMessage(Long userId, String sessionId, String content);

    void updateGeneratedMediaMessage(
            Long userId,
            String sessionId,
            Long messageId,
            String content,
            List<ChatMessageResourceDto> resources
    );

}
