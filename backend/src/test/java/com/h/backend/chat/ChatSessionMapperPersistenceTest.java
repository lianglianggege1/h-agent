package com.h.backend.chat;

import com.h.backend.chat.infrastructure.persistence.entity.ChatSessionEntity;
import com.h.backend.chat.infrastructure.persistence.entity.AgentSessionEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.AgentSessionMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatSessionMapper;
import com.h.backend.chat.infrastructure.persistence.mapper.ChatSessionMessageMapper;
import com.h.backend.chat.infrastructure.persistence.entity.ChatSessionMessageEntity;
import com.h.backend.user.infrastructure.persistence.entity.UserEntity;
import com.h.backend.user.infrastructure.persistence.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@Transactional
class ChatSessionMapperPersistenceTest {

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private AgentSessionMapper agentSessionMapper;

    @Autowired
    private UserMapper userMapper;
    @Autowired
    private ChatSessionMessageMapper messages;

    @Test
    void revisedTextIsPersistedAndOldRepliesDisappearWithoutDeletingReferencedRows() {
        var user = new UserEntity();
        user.setEmail("revision_" + UUID.randomUUID() + "@example.com"); user.setPasswordHash("hash");
        user.setStatus((short) 1); user.setCreatedAt(LocalDateTime.now()); user.setUpdatedAt(LocalDateTime.now());
        userMapper.insert(user);
        String sessionId = "revision-" + UUID.randomUUID();
        var agentSession = new AgentSessionEntity();
        agentSession.setSessionId(sessionId); agentSession.setUserId(user.getId()); agentSession.setAgentId("standard-chat");
        agentSession.setMessageCount(2); agentSession.setCreatedAt(LocalDateTime.now()); agentSession.setUpdatedAt(LocalDateTime.now());
        agentSessionMapper.insert(agentSession);
        var session = new ChatSessionEntity();
        session.setUserId(user.getId()); session.setSessionId(sessionId); session.setAgentId("standard-chat");
        session.setTitle("编辑测试"); session.setStatus("ACTIVE"); session.setMessageCount(2);
        session.setCreatedAt(LocalDateTime.now()); session.setUpdatedAt(LocalDateTime.now()); session.setLastActiveAt(LocalDateTime.now());
        chatSessionMapper.insert(session);
        var question = message(session, 1, "user", "原文字");
        var answer = message(session, 2, "assistant", "旧回复");
        messages.insert(question); messages.insert(answer);
        assertEquals(question.getId(), messages.selectLatestUser(sessionId).getId());

        messages.reviseUser(question.getId(), "改正后的文字");
        messages.supersedeAfter(sessionId, 1);
        chatSessionMapper.reviseLastUser(session.getId(), "改正后的文字");

        assertEquals("改正后的文字", messages.selectById(question.getId()).getContentText());
        assertTrue(messages.selectById(question.getId()).getPayloadJson().contains("改正后的文字"));
        assertEquals(question.getId(), messages.selectLatestUser(sessionId).getId());
        assertEquals(List.of(question.getId()), messages.selectPageByAgentSessionId(sessionId, 100, null)
                .stream().map(ChatSessionMessageEntity::getId).toList());
        assertEquals(1, messages.selectPageBySessionRecordId(session.getId(), sessionId, 100, null).size());
        assertNotNull(messages.selectById(answer.getId()), "Old execution/resource references must survive replacement");
        assertEquals("改正后的文字", chatSessionMapper.selectBySessionId(sessionId).getLastUserMessage());
        assertEquals(3, agentSessionMapper.nextMessageSequence(sessionId), "Superseded sequence numbers must never be reused");
    }

    private ChatSessionMessageEntity message(ChatSessionEntity session, int sequence, String role, String text) {
        var row = new ChatSessionMessageEntity();
        row.setSessionRecordId(session.getId()); row.setSessionId(session.getSessionId()); row.setUserId(session.getUserId());
        row.setSequenceNo(sequence); row.setRoleCode(role); row.setMessageType("user".equals(role) ? "USER" : "AI");
        row.setContentText(text); row.setPayloadJson("{\"content\":\"" + text + "\"}"); row.setCreatedAt(LocalDateTime.now());
        return row;
    }

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

        // chat_sessions 只保存顶级页面元数据；测试夹具也必须先登记统一 Agent Session 身份。
        AgentSessionEntity agentSession = new AgentSessionEntity();
        agentSession.setSessionId(sessionId);
        agentSession.setUserId(user.getId());
        agentSession.setAgentId(agentId);
        agentSession.setMessageCount(1);
        agentSession.setCreatedAt(now);
        agentSession.setUpdatedAt(now);
        agentSessionMapper.insert(agentSession);

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
