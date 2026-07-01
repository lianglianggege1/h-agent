package com.h.otheragents.a2a.application;

import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentProvider;
import io.a2a.spec.AgentSkill;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class A2AAgentCardApplicationService {

    private final OtherAgentsA2AProperties properties;

    public A2AAgentCardApplicationService(OtherAgentsA2AProperties properties) {
        this.properties = properties;
    }

    public AgentCard creativeWriterAgentCard() {
        return agentCard(
                "creative-writer",
                "根据指定主题生成故事",
                "/creative-wtiter/a2a",
                "creative-writer",
                "创意写作者",
                "根据主题生成故事初稿",
                List.of("story", "writing", "draft"),
                List.of("月球救援", "赛博朋克城市")
        );
    }

    public AgentCard audienceEditorAgentCard() {
        return agentCard(
                "audience-editor",
                "修改故事适配指定受众群体",
                "/audience-editor/a2a",
                "audience-editor",
                "受众编辑器",
                "修改故事适配指定受众群体",
                List.of("story", "audience", "editing"),
                List.of("把故事改写给儿童读者")
        );
    }

    public AgentCard styleEditorAgentCard() {
        return agentCard(
                "style-editor",
                "调整故事适配指定文风",
                "/style-editor/a2a",
                "style-editor",
                "风格编辑器",
                "调整故事适配指定文风",
                List.of("story", "style", "editing"),
                List.of("把故事改成赛博朋克风格")
        );
    }

    private AgentCard agentCard(
            String agentName,
            String description,
            String endpointPath,
            String skillId,
            String skillName,
            String skillDescription,
            List<String> tags,
            List<String> examples
    ) {
        String baseUrl = properties.normalizedPublicUrl();
        return new AgentCard.Builder()
                .name(agentName)
                .description(description)
                .url(baseUrl + endpointPath)
                .provider(new AgentProvider("h-agent other-agents", baseUrl))
                .version("0.1.0")
                .capabilities(new AgentCapabilities.Builder()
                        .streaming(false)
                        .pushNotifications(false)
                        .stateTransitionHistory(false)
                        .build())
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(List.of(new AgentSkill.Builder()
                        .id(skillId)
                        .name(skillName)
                        .description(skillDescription)
                        .tags(tags)
                        .examples(examples)
                        .inputModes(List.of("text/plain"))
                        .outputModes(List.of("text/plain"))
                        .build()))
                .build();
    }
}
