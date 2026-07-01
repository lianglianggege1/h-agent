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

    public AgentCard agentCard() {
        String baseUrl = properties.normalizedPublicUrl();
        return new AgentCard.Builder()
                .name("remote-creative-writer")
                .description("通过 A2A 暴露的远端创意写作者")
                .url(baseUrl + "/a2a")
                .provider(new AgentProvider("h-agent other-agents", baseUrl))
                .version("0.1.0")
                .capabilities(new AgentCapabilities.Builder()
                        .streaming(false)
                        .pushNotifications(false)
                        .stateTransitionHistory(false)
                        .build())
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(List.of(
                        new AgentSkill.Builder()
                                .id("creative-writer")
                                .name("创意写作者")
                                .description("根据主题生成故事初稿")
                                .tags(List.of("story", "writing", "draft"))
                                .examples(List.of("月球救援", "赛博朋克城市"))
                                .inputModes(List.of("text/plain"))
                                .outputModes(List.of("text/plain"))
                                .build(),
                        new AgentSkill.Builder()
                                .id("audience-editor")
                                .name("受众编辑器")
                                .description("修改故事适配指定受众群体")
                                .tags(List.of("story", "audience", "editing"))
                                .examples(List.of("把故事改写给儿童读者"))
                                .inputModes(List.of("text/plain"))
                                .outputModes(List.of("text/plain"))
                                .build(),
                        new AgentSkill.Builder()
                                .id("style-editor")
                                .name("风格编辑器")
                                .description("调整故事适配指定文风")
                                .tags(List.of("story", "style", "editing"))
                                .examples(List.of("把故事改成赛博朋克风格"))
                                .inputModes(List.of("text/plain"))
                                .outputModes(List.of("text/plain"))
                                .build()
                ))
                .build();
    }
}
