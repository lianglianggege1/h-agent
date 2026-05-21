package com.h.backend.chat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.chat.entity.AgentRunEntity;
import com.h.backend.chat.mapper.AgentRunMapper;
import com.h.backend.chat.service.AgentRunService;
import com.h.backend.chat.service.impl.AgentRunServiceImpl;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentRunServicePersistenceTest {

    @Test
    void shouldCompleteRunWithAssistantMessageAndToolSummary() throws Exception {
        AgentRunMapper agentRunMapper = mock(AgentRunMapper.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AgentRunService agentRunService = new AgentRunServiceImpl(agentRunMapper, objectMapper);

        AgentRunEntity saved = new AgentRunEntity();
        saved.setId(88L);
        saved.setStatus("RUNNING");
        saved.setToolCount(0);
        saved.setToolNamesJson("[]");

        when(agentRunMapper.selectById(88L)).thenReturn(saved);
        when(objectMapper.readValue(eq("[]"), any(TypeReference.class))).thenReturn(new LinkedHashSet<>());
        when(objectMapper.readValue(eq("[\"add\"]"), any(TypeReference.class))).thenReturn(new LinkedHashSet<>(java.util.Set.of("add")));
        when(objectMapper.writeValueAsString(any(LinkedHashSet.class)))
                .thenReturn("[\"add\"]", "[\"add\",\"search\"]");

        agentRunService.recordToolUsage(88L, "add");
        agentRunService.recordToolUsage(88L, "search");
        agentRunService.completeRun(88L, 301L);

        var summary = agentRunService.getById(88L);
        assertEquals("SUCCEEDED", summary.status());
        assertEquals(301L, summary.assistantMessageId());
        assertEquals(2, summary.toolCount());
        assertTrue(summary.toolNamesJson().contains("add"));
    }

    @Test
    void shouldMarkRunFailedWithErrorMessage() {
        AgentRunMapper agentRunMapper = mock(AgentRunMapper.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        AgentRunService agentRunService = new AgentRunServiceImpl(agentRunMapper, objectMapper);

        AgentRunEntity saved = new AgentRunEntity();
        saved.setId(89L);
        saved.setStatus("RUNNING");
        saved.setToolCount(0);
        saved.setToolNamesJson("[]");
        saved.setCompletedAt(LocalDateTime.now());
        when(agentRunMapper.selectById(89L)).thenReturn(saved);

        agentRunService.failRun(89L, "tool timeout");

        var summary = agentRunService.getById(89L);
        assertEquals("FAILED", summary.status());
        assertEquals("tool timeout", summary.errorMessage());
    }
}
