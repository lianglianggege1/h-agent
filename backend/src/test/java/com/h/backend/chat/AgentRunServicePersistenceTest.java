package com.h.backend.chat;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.h.backend.chat.infrastructure.persistence.entity.AgentRunEntity;
import com.h.backend.chat.infrastructure.persistence.mapper.AgentRunMapper;
import com.h.backend.chat.application.AgentRunService;
import com.h.backend.chat.application.impl.AgentRunServiceImpl;
import com.h.backend.common.exception.BusinessException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    @Test
    void requireOpenRunReturnsUniqueRunIdentity() {
        AgentRunMapper agentRunMapper = mock(AgentRunMapper.class);
        AgentRunService agentRunService = new AgentRunServiceImpl(agentRunMapper, new ObjectMapper());

        AgentRunEntity run = new AgentRunEntity();
        run.setId(51L);
        run.setUserId(7L);
        run.setSessionId("session-1");
        run.setUserMessageId(1001L);
        run.setStatus("RUNNING");
        run.setToolCount(0);
        run.setToolNamesJson("[]");
        when(agentRunMapper.selectOpenRuns("session-1", 7L)).thenReturn(List.of(run));

        var summary = agentRunService.requireOpenRun(7L, "session-1");
        assertEquals(51L, summary.id());
        assertEquals(1001L, summary.userMessageId());
        assertEquals("session-1", summary.sessionId());
    }

    @Test
    void requireOpenRunRejectsWhenNoOpenRun() {
        AgentRunMapper agentRunMapper = mock(AgentRunMapper.class);
        AgentRunService agentRunService = new AgentRunServiceImpl(agentRunMapper, new ObjectMapper());
        when(agentRunMapper.selectOpenRuns("session-1", 7L)).thenReturn(List.of());

        BusinessException error = assertThrows(BusinessException.class,
                () -> agentRunService.requireOpenRun(7L, "session-1"));
        assertEquals(40940, error.getCode());
    }

    @Test
    void requireOpenRunRejectsWhenMultipleOpenRuns() {
        AgentRunMapper agentRunMapper = mock(AgentRunMapper.class);
        AgentRunService agentRunService = new AgentRunServiceImpl(agentRunMapper, new ObjectMapper());
        AgentRunEntity first = new AgentRunEntity();
        first.setId(51L);
        AgentRunEntity second = new AgentRunEntity();
        second.setId(52L);
        when(agentRunMapper.selectOpenRuns("session-1", 7L)).thenReturn(List.of(first, second));

        BusinessException error = assertThrows(BusinessException.class,
                () -> agentRunService.requireOpenRun(7L, "session-1"));
        assertEquals(40940, error.getCode());
    }

    @Test
    void requireOpenRunRejectsBlankSessionWithoutQuerying() {
        AgentRunMapper agentRunMapper = mock(AgentRunMapper.class);
        AgentRunService agentRunService = new AgentRunServiceImpl(agentRunMapper, new ObjectMapper());

        assertThrows(BusinessException.class, () -> agentRunService.requireOpenRun(7L, " "));
        verify(agentRunMapper, never()).selectOpenRuns(any(), any());
    }
}
