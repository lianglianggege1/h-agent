package com.h.backend.memory.infrastructure.persistence;

import com.h.backend.chat.infrastructure.persistence.entity.AgentRunEntity;
import com.h.backend.chat.infrastructure.persistence.entity.ChatSessionMessageEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.AgentRunMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatSessionMessageMapper;
import com.h.backend.memory.application.TurnMessagePort;
import org.springframework.stereotype.Component;

@Component
public class TurnMessageAdapter implements TurnMessagePort {

    private final AgentRunMapper agentRunMapper;
    private final ChatSessionMessageMapper chatSessionMessageMapper;

    public TurnMessageAdapter(AgentRunMapper agentRunMapper,
                              ChatSessionMessageMapper chatSessionMessageMapper) {
        this.agentRunMapper = agentRunMapper;
        this.chatSessionMessageMapper = chatSessionMessageMapper;
    }

    @Override
    public TurnTexts loadTurnTexts(Long agentRunId, Long userMessageId, Long assistantMessageId) {
        Long resolvedUserMessageId = userMessageId;
        Long resolvedAssistantMessageId = assistantMessageId;
        if (agentRunId != null) {
            AgentRunEntity run = agentRunMapper.selectById(agentRunId);
            if (run != null) {
                if (resolvedUserMessageId == null) {
                    resolvedUserMessageId = run.getUserMessageId();
                }
                if (resolvedAssistantMessageId == null) {
                    resolvedAssistantMessageId = run.getAssistantMessageId();
                }
            }
        }
        if (resolvedUserMessageId == null || resolvedAssistantMessageId == null) {
            throw new IllegalStateException("turn messages are not fully persisted yet: run=" + agentRunId);
        }
        return new TurnTexts(
                readContent(resolvedUserMessageId),
                readContent(resolvedAssistantMessageId)
        );
    }

    private String readContent(Long messageId) {
        ChatSessionMessageEntity message = chatSessionMessageMapper.selectById(messageId);
        if (message == null) {
            throw new IllegalStateException("message not found: " + messageId);
        }
        return message.getContentText();
    }
}
