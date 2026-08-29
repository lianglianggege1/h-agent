package com.h.backend.chat.infrastructure.ai.a2a;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.langchain4j.ObservingAgentListener;
import com.h.agent.observability.langchain4j.PlatformAgentListener;
import com.h.backend.chat.domain.agent.AgentStepListener;
import com.h.backend.chat.infrastructure.ai.Agents;
import com.h.backend.chat.infrastructure.ai.carrentalassistant.domain.StoryInfo;
import com.h.backend.chat.domain.memory.ChatMemoryIdFactory;
import com.h.backend.chat.infrastructure.memory.RedisChatMemoryStore;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.agentic.observability.AgentListener;
import dev.langchain4j.agentic.scope.AgenticScope;
import dev.langchain4j.agentic.workflow.HumanInTheLoop;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Resource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
@ConditionalOnProperty(prefix = "agents.a2a.other-agents", name = "enabled", havingValue = "true", matchIfMissing = true)
public class A2AAgentConfig {

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ChatModel chatModel;

    @Resource
    private AgentStepListener agentStepListener;

    @Resource
    private ChatMemoryIdFactory chatMemoryIdFactory;

    @Resource
    private com.h.backend.chat.infrastructure.config.OtherAgentsA2AProperties otherAgentsA2AProperties;

    @Resource
    private AgentObservability agentObservability;

    /** 必须是同一实例：框架按实例去重继承的 listener，重复实例会导致事件双发。 */
    private AgentListener platformListener;

    private AgentListener platformListener() {
        if (platformListener == null) {
            platformListener = new PlatformAgentListener(agentStepListener,
                    new ObservingAgentListener(agentObservability));
        }
        return platformListener;
    }


    @Bean
    public A2AStoryAssistant a2aStoryAssistant() {
        // 必须在 a2aBuilder 构造 A2A Client（内部经 A2AHttpClientFactory 选型）之前完成初始化。
        com.h.backend.observability.a2a.ObservingA2AHttpClientProvider.initialize(agentObservability);

        A2ARemoteAgents.CreativeWriter creativeWriter = AgenticServices
                .a2aBuilder(otherAgentsA2AProperties.agentUrl("creative-writer"), A2ARemoteAgents.CreativeWriter.class)
                .listener(platformListener())
                .outputKey("story")
                .build();

        A2ARemoteAgents.AudienceEditor audienceEditor = AgenticServices
                .a2aBuilder(otherAgentsA2AProperties.agentUrl("audience-editor"), A2ARemoteAgents.AudienceEditor.class)
                .listener(platformListener())
                .outputKey("story")
                .build();

        A2ARemoteAgents.StyleEditor styleEditor = AgenticServices
                .a2aBuilder(otherAgentsA2AProperties.agentUrl("style-editor"), A2ARemoteAgents.StyleEditor.class)
                .listener(platformListener())
                .outputKey("story")
                .build();

        Agents.StoryInfoAgent storyInfoAgent = AgenticServices.agentBuilder(Agents.StoryInfoAgent.class)
                .chatModel(chatModel)
                .listener(platformListener())
                .chatMemoryProvider(scopedMemoryProvider("story-info-extractor"))
                .outputKey("storyInfo")
                .build();


        Agents.StyleScorer styleScorer = AgenticServices.agentBuilder(Agents.StyleScorer.class)
                .chatModel(chatModel)
                .listener(platformListener())
                .outputKey("score")
                .build();

        UntypedAgent storyCreator = AgenticServices.sequenceBuilder()
                .name("故事创作")
                .description("根据主题、风格和受众创作故事")
                .listener(platformListener())
                .subAgents(creativeWriter, audienceEditor)
                .outputKey("story")
                .build();

        UntypedAgent styleReviewLoop = AgenticServices.loopBuilder()
                .name("故事审核")
                .description("审核并评分给定故事以确保其与指定风格一致")
                .listener(platformListener())
                .subAgents(styleEditor, styleScorer)
                .maxIterations(5)
                .exitCondition(scope -> {
                    Double score = (Double) scope.readState("score", 0.0);
                    return score >= 0.8;
                })
                .outputKey("story")
                .build();

        UntypedAgent storyCreatorWithReview = AgenticServices.sequenceBuilder()
                .name("审核后的故事创作")
                .description("根据主题、风格和受众创作故事并进行审核")
                .listener(platformListener())
                .subAgents(storyCreator, styleReviewLoop)
                .outputKey("story")
                .build();

        HumanInTheLoop storyInfoClarifier = AgenticServices.humanInTheLoopBuilder()
                .description("向用户追问缺失的故事创作信息")
                .listener(platformListener())
                .outputKey("response")
                .responseProvider(scope -> storyInfoClarification((StoryInfo) scope.readState("storyInfo")))
                .build();

        UntypedAgent storyCreationFlow = AgenticServices.sequenceBuilder()
                .name("故事创作流程")
                .description("映射故事信息并执行故事创作审核")
                .listener(platformListener())
                .subAgents(Agents.StoryInfoMapper.class, storyCreatorWithReview)
                .output(scope -> scope.readState("story"))
                .outputKey("response")
                .build();

        UntypedAgent storyInfoGate = AgenticServices.conditionalBuilder()
                .name("故事信息完整性网关")
                .description("故事信息完整则进入创作流程，否则向用户追问缺失信息")
                .listener(platformListener())
                .subAgents(
                        "故事创作信息不完整",
                        scope -> !hasCompleteStoryInfo(scope),
                        storyInfoClarifier
                )
                .subAgents(
                        "故事创作信息完整",
                        A2AAgentConfig::hasCompleteStoryInfo,
                        storyCreationFlow
                )
                .outputKey("response")
                .build();

        return AgenticServices.sequenceBuilder(A2AStoryAssistant.class)
                .name("A2A故事创作代理")
                .description("根据主题、风格和受众创作故事并进行审核")
                .listener(platformListener())
                .subAgents(storyInfoAgent, storyInfoGate)
                .output(scope -> scope.readState("response"))
                .outputKey("response")
                .build();
    }

    private static boolean hasCompleteStoryInfo(AgenticScope scope) {
        return hasCompleteStoryInfo((StoryInfo) scope.readState("storyInfo"));
    }

    private static boolean hasCompleteStoryInfo(StoryInfo storyInfo) {
        return storyInfo != null
                && !isBlank(storyInfo.getTopic())
                && !isBlank(storyInfo.getStyle())
                && !isBlank(storyInfo.getAudience());
    }

    private ChatMemoryProvider scopedMemoryProvider(String scopeKey) {
        return memoryId -> MessageWindowChatMemory.builder()
                .id(chatMemoryIdFactory.scopedMemoryId(String.valueOf(memoryId), scopeKey))
                .maxMessages(10)
                .alwaysKeepSystemMessageFirst(true)
                .chatMemoryStore(redisChatMemoryStore)
                .build();
    }

    private static String storyInfoClarification(StoryInfo storyInfo) {
        List<String> missingFields = new ArrayList<>();
        if (storyInfo == null || isBlank(storyInfo.getTopic())) {
            missingFields.add("故事主题");
        }
        if (storyInfo == null || isBlank(storyInfo.getStyle())) {
            missingFields.add("故事风格");
        }
        if (storyInfo == null || isBlank(storyInfo.getAudience())) {
            missingFields.add("目标受众");
        }
        return "为了继续创作故事，请补充：" + String.join("、", missingFields) + "。";
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

}
