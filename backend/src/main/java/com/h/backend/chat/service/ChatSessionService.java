package com.h.backend.chat.service;

import com.h.backend.chat.dto.ChatSessionBootstrapDto;
import com.h.backend.chat.dto.ChatMessageResourceDto;
import com.h.backend.chat.dto.ChatSessionMessageDto;
import com.h.backend.chat.dto.ChatSessionMessagesPageDto;
import com.h.backend.chat.dto.ChatSessionMetaDto;
import com.h.backend.chat.dto.ChatSessionOpenDto;
import com.h.backend.chat.dto.ChatSessionSummaryDto;
import com.h.backend.chat.model.ChatMessagePayload;

import java.util.List;

public interface ChatSessionService {

    ChatSessionBootstrapDto bootstrap(Long userId);

    ChatSessionOpenDto createSession(Long userId, Long promptId, String currentSessionId);

    ChatSessionOpenDto chooseActiveSession(Long userId, String selectedSessionId);

    ChatSessionOpenDto activateHistorySession(Long userId, String targetSessionId, String currentSessionId);

    ChatSessionMetaDto getSessionDetail(Long userId, String sessionId);

    ChatSessionMessagesPageDto getSessionMessages(Long userId, String sessionId, int limit, Integer beforeSeq);

    List<ChatSessionSummaryDto> listHistory(Long userId, int page, int size);

    void archiveExpiredSessions();

    void assertActiveSession(Long userId, String sessionId, Long promptId);

    Long appendUserMessage(Long userId, String sessionId, String userMessage);

    Long appendBlockedMessage(Long userId, String sessionId, String blockedMessage);

    Long appendReasoningMessage(Long userId, String sessionId, String reasoningMessage);

    Long appendAssistantMessage(Long userId, String sessionId, String assistantMessage);

    ChatSessionMessageDto appendImageMessage(
            Long userId,
            String sessionId,
            String imagePrompt,
            ChatMessagePayload payload,
            List<ChatMessageResourceDto> resources
    );
}
