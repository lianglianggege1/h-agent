package com.h.otheragents.a2a.server;

import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.a2a.export.A2AAgentExport;
import org.a2aproject.sdk.spec.AgentCapabilities;
import org.a2aproject.sdk.spec.AgentCard;
import org.a2aproject.sdk.spec.AgentInterface;
import org.a2aproject.sdk.spec.AgentProvider;
import org.a2aproject.sdk.spec.AgentSkill;

import java.util.List;

public class A2AAgentCardFactory {

    private final OtherAgentsA2AProperties properties;

    public A2AAgentCardFactory(OtherAgentsA2AProperties properties) {
        this.properties = properties;
    }

    public AgentCard card(A2AAgentExport export) {
        String baseUrl = properties.normalizedPublicUrl();
        return AgentCard.builder()
                .name(export.id())
                .description(export.method().publicDescription())
                .url(properties.agentUrl(export.id()))
                .provider(new AgentProvider("h-agent other-agents", baseUrl))
                .version(properties.getVersion())
                .capabilities(AgentCapabilities.builder()
                        .streaming(false)
                        .pushNotifications(false)
                        .extendedAgentCard(false)
                        .build())
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(skills(export))
                .supportedInterfaces(List.of(new AgentInterface("JSONRPC", properties.agentUrl(export.id()), null, AgentInterface.CURRENT_PROTOCOL_VERSION)))
                .build();
    }
    private List<AgentSkill> skills(A2AAgentExport export) {
        if ("story-review".equals(export.id())) {
            return List.of(
                    reviewSkill("style-score", "稿件评分", "按目标文风提供分数和具体理由"),
                    reviewSkill("style-review", "稿件审核", "依据事实核查和文风要求编辑稿件"));
        }
        return List.of(AgentSkill.builder()
                .id(export.id()).name(export.method().publicName())
                .description(export.method().publicDescription()).tags(List.of("langchain4j", "a2a"))
                .examples(List.of(String.join(", ", export.method().inputKeys())))
                .inputModes(List.of("text/plain")).outputModes(List.of("text/plain")).build());
    }

    private AgentSkill reviewSkill(String id, String label, String description) {
        return AgentSkill.builder().id(id).name(label).description(description)
                .tags(List.of("writing", "review")).inputModes(List.of("text/plain"))
                .outputModes(List.of("text/plain")).build();
    }
}
