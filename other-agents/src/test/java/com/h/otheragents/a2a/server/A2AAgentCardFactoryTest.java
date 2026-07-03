package com.h.otheragents.a2a.server;

import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.a2a.export.A2AAgentExport;
import com.h.otheragents.a2a.export.A2AAgentExports;
import dev.langchain4j.agentic.Agent;
import dev.langchain4j.service.V;
import org.a2aproject.sdk.spec.AgentCard;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class A2AAgentCardFactoryTest {

    interface DraftAgent {

        @Agent(name = "创意写作者", description = "根据主题生成故事初稿", outputKey = "story")
        String generate(@V("topic") String topic);
    }

    @Test
    void cardUsesExportMetadataAndUnifiedEndpoint() {
        OtherAgentsA2AProperties properties = new OtherAgentsA2AProperties();
        properties.setPublicUrl("http://localhost:8082/");
        DraftAgent bean = topic -> "draft:" + topic;
        A2AAgentExport export = A2AAgentExports.builder()
                .export("creative-writer", bean, DraftAgent.class, "generate")
                .build()
                .list()
                .getFirst();

        AgentCard card = new A2AAgentCardFactory(properties).card(export);

        assertEquals("creative-writer", card.name());
        assertEquals("根据主题生成故事初稿", card.description());
        assertEquals("http://localhost:8082/a2a/agents/creative-writer", card.url());
        assertFalse(card.capabilities().streaming());
        assertEquals("creative-writer", card.skills().getFirst().id());
        assertEquals("创意写作者", card.skills().getFirst().name());
    }
}
