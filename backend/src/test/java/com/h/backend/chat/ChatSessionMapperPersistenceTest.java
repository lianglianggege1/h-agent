package com.h.backend.chat;

import com.h.backend.chat.entity.ChatSessionEntity;
import com.h.backend.chat.mapper.ChatSessionMapper;
import com.h.backend.user.entity.UserEntity;
import com.h.backend.user.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class ChatSessionMapperPersistenceTest {

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private UserMapper userMapper;

    @Test
    void customSelectsReturnAgentId() {
        UserEntity user = new UserEntity();
        user.setEmail("session_mapper_" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("hash-value");
        user.setStatus((short) 1);
        user.setCreatedAt(LocalDateTime.now());
        user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);

        String sessionId = "session-" + UUID.randomUUID();
        String agentId = "car-rental-assistant";
        LocalDateTime now = LocalDateTime.now();

        ChatSessionEntity session = new ChatSessionEntity();
        session.setUserId(user.getId());
        session.setSessionId(sessionId);
        session.setPromptId(null);
        session.setAgentId(agentId);
        session.setTitle("Mapper agent test");
        session.setStatus("ACTIVE");
        session.setLastUserMessage("hello");
        session.setMessageCount(1);
        session.setLastActiveAt(now);
        session.setCreatedAt(now);
        session.setUpdatedAt(now);
        chatSessionMapper.insert(session);

        ChatSessionEntity bySessionId = chatSessionMapper.selectBySessionId(sessionId);
        List<ChatSessionEntity> active = chatSessionMapper.selectActiveByUserId(user.getId());
        List<ChatSessionEntity> history = chatSessionMapper.selectHistoryByUserId(user.getId(), 10, 0);
        List<ChatSessionEntity> expired = chatSessionMapper.selectExpiredActiveSessions(user.getId(), now.plusSeconds(1));

        assertEquals(agentId, bySessionId.getAgentId());
        assertEquals(agentId, active.getFirst().getAgentId());
        assertEquals(agentId, history.getFirst().getAgentId());
        assertEquals(agentId, expired.getFirst().getAgentId());
    }
}
