package com.h.backend.chat.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.chat.entity.AgentRunEntity;
import com.h.backend.chat.mapper.AgentRunMapper;
import com.h.backend.chat.service.AgentRunService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AgentRunServiceImpl implements AgentRunService {

    private final AgentRunMapper agentRunMapper;
    @SuppressWarnings("unused")
    private final ObjectMapper objectMapper;

    public AgentRunServiceImpl(AgentRunMapper agentRunMapper, ObjectMapper objectMapper) {
        this.agentRunMapper = agentRunMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentRunHandle createRun(
            String sessionId,
            Long userId,
            Long promptId,
            Long userMessageId,
            String modelName,
            String langfuseTraceId
    ) {
        AgentRunEntity entity = new AgentRunEntity();
        LocalDateTime now = LocalDateTime.now();
        entity.setSessionId(sessionId);
        entity.setUserId(userId);
        entity.setPromptId(promptId);
        entity.setUserMessageId(userMessageId);
        entity.setStatus("RUNNING");
        entity.setModelName(modelName);
        entity.setLangfuseTraceId(langfuseTraceId);
        entity.setToolCount(0);
        entity.setToolNamesJson("[]");
        entity.setStartedAt(now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        agentRunMapper.insert(entity);
        if (entity.getId() == null) {
            entity.setId(-1L);
        }
        return new AgentRunHandle(entity.getId());
    }
}
