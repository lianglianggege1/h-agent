package com.h.backend.chat.application.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.chat.infrastructure.persistence.entity.AgentRunEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.AgentRunMapper;
import com.h.backend.chat.domain.model.AgentRunSummary;
import com.h.backend.chat.application.AgentRunService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;

@Service
public class AgentRunServiceImpl implements AgentRunService {

    private final AgentRunMapper agentRunMapper;
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
        return new AgentRunHandle(entity.getId());
    }

    @Override
    public void updateTraceId(Long runId, String langfuseTraceId) {
        AgentRunEntity entity = agentRunMapper.selectById(runId);
        entity.setLangfuseTraceId(langfuseTraceId);
        entity.setUpdatedAt(LocalDateTime.now());
        agentRunMapper.updateById(entity);
    }

    @Override
    public void recordToolUsage(Long runId, String toolName) {
        AgentRunEntity entity = agentRunMapper.selectById(runId);
        LinkedHashSet<String> names = readToolNames(entity.getToolNamesJson());
        names.add(toolName);
        entity.setToolCount(entity.getToolCount() == null ? 1 : entity.getToolCount() + 1);
        entity.setToolNamesJson(writeToolNames(names));
        entity.setUpdatedAt(LocalDateTime.now());
        agentRunMapper.updateById(entity);
    }

    @Override
    public void completeRun(Long runId, Long assistantMessageId) {
        AgentRunEntity entity = agentRunMapper.selectById(runId);
        LocalDateTime now = LocalDateTime.now();
        entity.setAssistantMessageId(assistantMessageId);
        entity.setStatus("SUCCEEDED");
        entity.setCompletedAt(now);
        entity.setUpdatedAt(now);
        agentRunMapper.updateById(entity);
    }

    @Override
    public void failRun(Long runId, String errorMessage) {
        AgentRunEntity entity = agentRunMapper.selectById(runId);
        LocalDateTime now = LocalDateTime.now();
        entity.setStatus("FAILED");
        entity.setErrorMessage(errorMessage);
        entity.setCompletedAt(now);
        entity.setUpdatedAt(now);
        agentRunMapper.updateById(entity);
    }

    @Override
    public AgentRunSummary getById(Long runId) {
        AgentRunEntity entity = agentRunMapper.selectById(runId);
        return new AgentRunSummary(
                entity.getId(),
                entity.getStatus(),
                entity.getAssistantMessageId(),
                entity.getToolCount() == null ? 0 : entity.getToolCount(),
                entity.getToolNamesJson(),
                entity.getErrorMessage(),
                entity.getCompletedAt()
        );
    }

    private LinkedHashSet<String> readToolNames(String json) {
        try {
            return objectMapper.readValue(
                    json == null || json.isBlank() ? "[]" : json,
                    new TypeReference<LinkedHashSet<String>>() {
                    }
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to parse tool names json", ex);
        }
    }

    private String writeToolNames(LinkedHashSet<String> names) {
        try {
            return objectMapper.writeValueAsString(names);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize tool names json", ex);
        }
    }
}
