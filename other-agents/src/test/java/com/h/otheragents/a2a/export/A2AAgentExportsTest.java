package com.h.otheragents.a2a.export;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class A2AAgentExportsTest {

    interface DraftAgent {

        @Agent(name = "创意写作者", description = "根据主题生成故事初稿", outputKey = "story")
        String generate(@V("topic") String topic);
    }

    @Test
    void exportReadsMethodMetadataFromLangChain4jAnnotations() {
        DraftAgent bean = topic -> "draft:" + topic;

        A2AAgentExports exports = A2AAgentExports.builder()
                .export("creative-writer", bean, DraftAgent.class, "generate")
                .build();

        A2AAgentExport export = exports.list().getFirst();

        assertEquals("creative-writer", export.id());
        assertSame(bean, export.agentBean());
        assertEquals(DraftAgent.class, export.agentInterface());
        assertEquals("generate", export.method().method().getName());
        assertEquals(List.of("topic"), export.method().inputKeys());
        assertEquals("story", export.method().outputKey());
        assertEquals("创意写作者", export.method().publicName());
        assertEquals("根据主题生成故事初稿", export.method().publicDescription());
    }
}
