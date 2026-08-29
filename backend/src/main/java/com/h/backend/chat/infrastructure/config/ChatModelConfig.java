package com.h.backend.chat.infrastructure.config;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.langchain4j.ObservingChatModel;
import com.h.agent.observability.langchain4j.ObservingStreamingChatModel;
import com.h.agent.observability.langchain4j.ObservingToolProvider;
import com.h.backend.chat.infrastructure.ai.HAssistant;
import com.h.backend.chat.infrastructure.memory.RedisChatMemoryStore;
import com.h.backend.chat.application.SystemPromptService;
import com.h.backend.chat.infrastructure.filesystem.AssistantFileProperties;
import com.h.backend.chat.infrastructure.tools.FileDeliveryTool;
import com.h.backend.chat.infrastructure.tools.FilesystemTool;
import com.h.backend.chat.infrastructure.tools.HToolArgumentsErrorHandler;
import com.h.backend.chat.infrastructure.tools.HToolExecutionErrorHandler;
import com.h.backend.chat.infrastructure.tools.ImageGenerationTool;
import com.h.backend.chat.infrastructure.tools.ShellTool;
import com.h.backend.chat.infrastructure.tools.ShellToolProperties;
import com.h.backend.chat.infrastructure.tools.WebSearchTool;
import com.h.backend.generation.interfaces.tool.TextToVideoTool;
import com.h.backend.generation.interfaces.tool.ImageToVideoTool;
import com.h.backend.skill.application.SkillRuntimeService;
import com.h.backend.skill.application.SkillRuntimeToolProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.DisabledChatModel;
import dev.langchain4j.model.chat.DisabledStreamingChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import dev.langchain4j.service.tool.ToolService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableConfigurationProperties({ImageGenerationProperties.class, AssistantFileProperties.class, ShellToolProperties.class})
public class ChatModelConfig {

    private static final String LANGCHAIN4J_HTTP_REQUEST_LOGGER =
            "dev.langchain4j.http.client.log.HttpRequestLogger";

    @Resource
    private SystemPromptService systemPromptService;

    @Resource
    private RedisChatMemoryStore redisChatMemoryStore;

    @Resource
    private ImageGenerationTool imageGenerationTool;

    @Resource
    private FilesystemTool filesystemTool;

    @Resource
    private FileDeliveryTool fileDeliveryTool;

    @Resource
    private ShellTool shellTool;

    @Resource
    private WebSearchTool webSearchTool;

    @Resource
    private TextToVideoTool textToVideoTool;

    @Resource
    private ImageToVideoTool imageToVideoTool;

    @Resource
    private HToolArgumentsErrorHandler hToolArgumentsErrorHandler;

    @Resource
    private HToolExecutionErrorHandler hToolExecutionErrorHandler;

    @Resource
    private SkillRuntimeService skillRuntimeService;

    @Resource
    private SkillRuntimeToolProvider skillRuntimeToolProvider;

    @Bean
    public StreamingChatModel streamingChatModel(AgentObservability observability) {
        var environment = ChatModelEnvironment.load(Path.of(""));
        if (environment.isEmpty()) {
            return new DisabledStreamingChatModel();
        }
        ChatModelEnvironment settings = environment.orElseThrow();
        StreamingChatModel delegate = AnthropicStreamingChatModel.builder()
                .apiKey(settings.apiKey())
                .baseUrl(settings.baseUrl())
                .modelName(settings.modelName())
                .thinkingBudgetTokens(8192)
                .maxTokens(16384)
                .returnThinking(true)
                .timeout(Duration.ofSeconds(120))
                .logRequests(true)
                .logResponses(true)
                .logger(LoggerFactory.getLogger(LANGCHAIN4J_HTTP_REQUEST_LOGGER))
                .build();
        return new ObservingStreamingChatModel(delegate, observability, "anthropic");
    }

    @Bean
    public ChatModel chatModel(AgentObservability observability) {
        var environment = ChatModelEnvironment.load(Path.of(""));
        if (environment.isEmpty()) {
            return new DisabledChatModel();
        }
        ChatModelEnvironment settings = environment.orElseThrow();
        ChatModel delegate = AnthropicChatModel.builder()
                .apiKey(settings.apiKey())
                .baseUrl(settings.baseUrl())
                .modelName(settings.modelName())
                .thinkingBudgetTokens(8192)
                .maxTokens(16384)
                .timeout(Duration.ofSeconds(120))
                .logRequests(true)
                .logResponses(true)
                .logger(LoggerFactory.getLogger(LANGCHAIN4J_HTTP_REQUEST_LOGGER))
                .build();
        return new ObservingChatModel(delegate, observability, "anthropic");
    }

    @Bean
    public HAssistant hAssistant(StreamingChatModel streamingChatModel,
                                 RetrievalAugmentor knowledgeRetrievalAugmentor,
                                 ObjectProvider<McpToolProvider> mcpToolProvider,
                                 AgentObservability observability) {
        List<ToolProvider> toolProviders = new ArrayList<>();
        // 静态工具走 provider 接缝注册：观测装饰器在每次请求构建时捕获当前观测上下文，
        // 使工具 Span 正确挂在所属 Generation 下。@CompensatingAction 类框架补偿语义
        // 在该路径不可用（当前工具集未使用）。
        toolProviders.add(staticToolsProvider(observability, List.of(
                imageGenerationTool, textToVideoTool, imageToVideoTool,
                filesystemTool, fileDeliveryTool, shellTool, webSearchTool)));
        // Skill 工具集按请求固定快照解析：activate_skill / read_skill_resource 由
        // SkillRuntimeToolProvider 依据本次执行的 Runtime Snapshot 提供。
        toolProviders.add(new ObservingToolProvider(skillRuntimeToolProvider, observability, "langchain4j"));
        toolProviders.add(new ObservingToolProvider(request -> {
            McpToolProvider provider = mcpToolProvider.getIfAvailable();
            if (provider == null) {
                return ToolProviderResult.builder().build();
            }
            return provider.provideTools(request);
        }, observability, "langchain4j"));

        return AiServices.builder(HAssistant.class)
                .streamingChatModel(streamingChatModel)
//                .retrievalAugmentor(knowledgeRetrievalAugmentor)
                // 不同用户的系统提示词不一样
                .systemMessageProvider(memoryId -> {
                    String[] parts = memoryId.toString().split(":", 3);
                    Long userId = Long.valueOf(parts[0]);
                    Long promptId = Long.valueOf(parts[1]);

                    String systemMessage = systemPromptService.getSystemPrompt(userId, promptId);
                    String skillsSection = skillRuntimeService.skillsSystemMessage(memoryId.toString());
                    if (skillsSection == null) {
                        return systemMessage;
                    }
                    return systemMessage + "\n\n" + skillsSection;
                })
                .toolArgumentsErrorHandler(hToolArgumentsErrorHandler)
                .toolExecutionErrorHandler(hToolExecutionErrorHandler)
                .executeToolsConcurrently() // 并发调用工具
                .toolProviders(toolProviders)
                // 记忆模块提供者
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(1000)
                        .alwaysKeepSystemMessageFirst(true)
                        .chatMemoryStore(redisChatMemoryStore)
                        .build())
                .build();
    }

    private static ToolProvider staticToolsProvider(AgentObservability observability, List<Object> toolObjects) {
        List<AiServiceTool> tools = new ArrayList<>();
        for (Object toolObject : toolObjects) {
            tools.addAll(ToolService.findTools(toolObject));
        }
        return new ObservingToolProvider(request -> new ToolProviderResult(tools), observability, "langchain4j");
    }
}
