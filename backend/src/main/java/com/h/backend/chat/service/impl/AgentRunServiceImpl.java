package com.h.backend.chat.service.impl;

import com.h.backend.chat.entity.AgentRunEntity;
import com.h.backend.chat.mapper.AgentRunMapper;
import com.h.backend.chat.service.AgentRunService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class AgentRunServiceImpl implements AgentRunService {

    private final AgentRunMapper agentRunMapper;

    public AgentRunServiceImpl(AgentRunMapper agentRunMapper) {
        this.agentRunMapper = agentRunMapper;
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
        return new AgentRunHandle(entity.getId());
    }
}
