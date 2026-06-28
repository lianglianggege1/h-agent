package com.h.backend.chat.service;

import com.h.backend.chat.dto.ChatSessionBootstrapDto;
import com.h.backend.chat.dto.ChatMessageResourceUseDto;
import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.dto.ChatSessionMessagesPageDto;
import com.h.backend.chat.dto.ChatSessionMetaDto;
import com.h.backend.chat.dto.ChatSessionOpenDto;
import com.h.backend.chat.dto.ChatSessionSummaryDto;
import com.h.backend.chat.model.ChatMessagePayload;
import com.h.backend.chat.storage.StoredResource;

import java.util.List;
import java.util.Map;

public interface ChatSessionService {

    ChatSessionBootstrapDto bootstrap(Long userId);

    ChatSessionOpenDto createSession(Long userId, Long promptId, String agentId, String currentSessionId);

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
}
