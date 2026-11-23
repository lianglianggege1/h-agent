package com.h.otheragents.a2a.story;

import com.alibaba.nacos.api.ai.AiService;
import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.NoopAgentObservability;
import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class StoryReviewAgentsWiringTest {
    @Test
    void startsAndUsesTheA2aModelWhenTeamProvidesAnotherChatModel() {
        var a2aModel = mock(ChatModel.class);
        var teamModel = mock(ChatModel.class);
        when(a2aModel.chat(anyString())).thenReturn("审稿结果");

        new ApplicationContextRunner()
                .withPropertyValues("other-agents.a2a.story.enabled=true")
                .withUserConfiguration(StoryReviewAgents.class)
                .withBean("chatModel", ChatModel.class, () -> a2aModel)
                .withBean("teamWorkerChatModel", ChatModel.class, () -> teamModel)
                .withBean(AiService.class, () -> mock(AiService.class))
                .withBean(AgentObservability.class, NoopAgentObservability::getInstance)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var agents = context.getBean(StoryReviewAgents.class);
                    assertThat(agents.reviewer().assess("测试稿件", "style-score")).isEqualTo("审稿结果");
                    assertThat(agents.checker().check("测试稿件")).isEqualTo("审稿结果");
                    verifyNoInteractions(teamModel);
                });
    }
}
