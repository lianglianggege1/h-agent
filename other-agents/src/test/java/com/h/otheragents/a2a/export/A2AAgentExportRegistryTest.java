package com.h.otheragents.a2a.export;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class A2AAgentExportRegistryTest {

    interface EchoAgent {

        @Agent(outputKey = "response")
        String echo(@V("question") String question);
    }

    @Test
    void requireReturnsExportById() {
        EchoAgent bean = question -> "echo:" + question;
        A2AAgentExportRegistry registry = new A2AAgentExportRegistry(A2AAgentExports.builder()
                .export("echo", bean, EchoAgent.class, "echo")
                .build());

        assertEquals("echo", registry.require("echo").id());
    }

    @Test
    void requireRejectsUnknownAgentId() {
        A2AAgentExportRegistry registry = new A2AAgentExportRegistry(A2AAgentExports.builder().build());

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> registry.require("missing"));

        assertEquals("A2A agent not found: missing", error.getMessage());
    }
}
