package com.h.backend.chat.config;

import com.h.backend.chat.infrastructure.config.OtherAgentsA2AProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class OtherAgentsA2APropertiesTest {
    @Test
    void pinsAgentVersionAndEnablesA2AByDefault() {
        var properties = new OtherAgentsA2AProperties();
        assertTrue(properties.isEnabled());
        assertEquals("0.1.0", properties.getVersion());
        assertThrows(IllegalArgumentException.class, () -> properties.setVersion(" "));
    }
}
