package com.h.backend.chat.ai;

import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AgentsTest {

    @Test
    void bankerAgentSeedsRequestStateForSupervisor() throws NoSuchMethodException {
        Method chat = Agents.BankerAgent.class.getMethod("chat", String.class, String.class);

        V request = chat.getParameters()[1].getAnnotation(V.class);

        assertEquals("request", request.value());
    }
}
