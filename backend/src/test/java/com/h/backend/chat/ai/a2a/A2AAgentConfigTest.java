package com.h.backend.chat.ai.a2a;

import com.h.backend.chat.infrastructure.ai.a2a.A2AAgentConfig;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class A2AAgentConfigTest {

    @Test
    void configDoesNotInjectCustomRemoteAgentRegistry() {
        boolean hasRegistryField = Arrays.stream(A2AAgentConfig.class.getDeclaredFields())
                .map(Field::getType)
                .anyMatch(type -> type.getSimpleName().equals("A2ARemoteAgentRegistry"));

        assertFalse(hasRegistryField);
    }
}
