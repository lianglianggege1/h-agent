package com.h.backend.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.backend.chat.mapper.AgentRunMapper;
import com.h.backend.chat.service.impl.AgentRunServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@ExtendWith(MockitoExtension.class)
class AgentRunServicePersistenceTest {

    @Mock
    private AgentRunMapper agentRunMapper;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private AgentRunServiceImpl agentRunService;

    @Test
    void shouldCreateAgentRunSkeleton() {
        var run = agentRunService.createRun(
                "session-" + System.nanoTime(),
                1L,
                2L,
                3L,
                "gpt-test",
                null
        );

        assertNotNull(run.id());
    }
}
