package com.h.backend.chat.infrastructure.config;

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
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderResult;
import dev.langchain4j.service.tool.search.simple.SimpleToolSearchStrategy;
import dev.langchain4j.skills.Skills;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

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

    @Bean
    public StreamingChatModel streamingChatModel() {

        Path envPath = Path.of(".env");
        if (!Files.exists(envPath)) {
            return new DisabledStreamingChatModel();
        }

        Properties properties = new Properties();

        try (var reader = Files.newBufferedReader(envPath)) {
            properties.load(reader);
            return AnthropicStreamingChatModel.builder()
                    .apiKey(properties.getProperty("API_KEY"))
                    .baseUrl("https://api.minimaxi.com/anthropic/v1")
                    .modelName(properties.getProperty("MODEL_NAME"))
                    .thinkingBudgetTokens(8192)
                    .maxTokens(16384)
                    .returnThinking(true)
                    .timeout(Duration.ofSeconds(120))
                    .logRequests(true)
                    .logResponses(true)
                    .logger(LoggerFactory.getLogger(LANGCHAIN4J_HTTP_REQUEST_LOGGER))
                    .build();

        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load .env file", ex);
        }
    }

    @Bean
    public ChatModel chatModel() {
        Path envPath = Path.of(".env");
        if (!Files.exists(envPath)) {
            return new DisabledChatModel();
        }
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(envPath)) {
            properties.load(reader);
            return AnthropicChatModel.builder()
                    .apiKey(properties.getProperty("API_KEY"))
                    .baseUrl("https://api.minimaxi.com/anthropic/v1")
                    .modelName(properties.getProperty("MODEL_NAME"))
                    .thinkingBudgetTokens(8192)
                    .maxTokens(16384)
                    .timeout(Duration.ofSeconds(120))
                    .logRequests(true)
                    .logResponses(true)
                    .logger(LoggerFactory.getLogger(LANGCHAIN4J_HTTP_REQUEST_LOGGER))
                    .build();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to load .env file", ex);
        }
    }

    @Bean
    public HAssistant hAssistant(StreamingChatModel streamingChatModel,
                                 RetrievalAugmentor knowledgeRetrievalAugmentor,
                                 ObjectProvider<McpToolProvider> mcpToolProvider,
                                 ObjectProvider<Skills> skillsProvider) {
        Skills skills = skillsProvider.getIfAvailable();
        List<ToolProvider> toolProviders = new ArrayList<>();
        if (skills != null) {
            toolProviders.add(skills.toolProvider());
        }
        toolProviders.add(request -> {
            McpToolProvider provider = mcpToolProvider.getIfAvailable();
            if (provider == null) {
                return ToolProviderResult.builder().build();
            }
            return provider.provideTools(request);
        });

        return AiServices.builder(HAssistant.class)
                .streamingChatModel(streamingChatModel)
//                .retrievalAugmentor(knowledgeRetrievalAugmentor)
                // 不同用户的系统提示词不一样
                .systemMessageProvider(memoryId -> {
                    String[] parts = memoryId.toString().split(":", 3);
                    Long userId = Long.valueOf(parts[0]);
                    Long promptId = Long.valueOf(parts[1]);

                    String systemMessage = systemPromptService.getSystemPrompt(userId, promptId);
                    if (skills == null) {
                        return systemMessage;
                    }
                    return systemMessage + "\n\n" + skillsSystemMessage(skills);
                })
                .tools(imageGenerationTool, textToVideoTool, imageToVideoTool, filesystemTool, fileDeliveryTool, shellTool, webSearchTool)
//                .toolSearchStrategy(SimpleToolSearchStrategy.builder().build())
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

    private String skillsSystemMessage(Skills skills) {
        return "You have access to the following skills:\n"
                + skills.formatAvailableSkills()
                + "\nWhen the user's request relates to one of these skills, first call `activate_skill` "
                + "before following its instructions. Use `read_skill_resource` when a referenced resource is needed. "
                + "Skill-provided scripts must not be executed.";
    }
}
