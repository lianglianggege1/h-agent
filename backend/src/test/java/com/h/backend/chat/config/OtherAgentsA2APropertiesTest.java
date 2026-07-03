package com.h.backend.chat.config;

import com.h.backend.chat.infrastructure.config.OtherAgentsA2AProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OtherAgentsA2APropertiesTest {

    @Test
    void agentUrlAppendsUnifiedA2AAgentPath() {
        OtherAgentsA2AProperties properties = new OtherAgentsA2AProperties();
        properties.setBaseUrl("http://localhost:8082/");

        assertEquals(
                "http://localhost:8082/a2a/agents/creative-writer",
                properties.agentUrl("creative-writer")
        );
    }

    @Test
    void defaultsKeepOtherAgentsA2AEnabled() {
        OtherAgentsA2AProperties properties = new OtherAgentsA2AProperties();

        assertTrue(properties.isEnabled());
        assertEquals("http://localhost:8082", properties.getBaseUrl());
    }
}
