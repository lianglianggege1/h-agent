package com.h.backend.memory.infrastructure.config;

import com.h.backend.chat.domain.agent.ChatAgentIds;
import com.h.backend.memory.application.LongTermMemoryRuntime;
import com.h.backend.memory.application.NoopLongTermMemoryRuntime;
import com.h.backend.memory.application.UserMemoryCatalog;
import com.h.backend.memory.application.DisabledUserMemoryCatalog;
import com.h.backend.memory.domain.AgentMemoryPolicyCatalog;
import com.h.backend.memory.infrastructure.LongTermMemoryRuntimeImpl;
import com.h.backend.memory.infrastructure.UserMemoryCatalogImpl;
import com.h.backend.memory.infrastructure.langchain4j.ConversationContextAugmentor;
import com.h.backend.memory.infrastructure.langchain4j.LongTermMemoryContentRetriever;
import com.h.backend.memory.infrastructure.mem0.Mem0Gateway;
import com.h.backend.memory.infrastructure.mem0.Mem0HttpGateway;
import com.h.backend.memory.infrastructure.persistence.mapper.LongTermMemoryRecordMapper;
import com.h.backend.memory.infrastructure.persistence.mapper.MemoryCaptureOutboxMapper;
import com.h.backend.memory.infrastructure.persistence.mapper.MemoryOperationMapper;
import dev.langchain4j.rag.RetrievalAugmentor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableConfigurationProperties(LongTermMemoryProperties.class)
public class LongTermMemoryConfig {

    private static final Logger log = LoggerFactory.getLogger(LongTermMemoryConfig.class);

    @Bean
    @ConditionalOnProperty(prefix = "memory.long-term", name = "enabled", havingValue = "true")
    public Mem0Gateway mem0Gateway(LongTermMemoryProperties properties, ObjectMapper objectMapper) {
        validateOrFailFast(properties);
        return new Mem0HttpGateway(properties, objectMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "memory.long-term", name = "enabled", havingValue = "true")
    public LongTermMemoryRuntime longTermMemoryRuntime(Mem0Gateway mem0Gateway,
                                                       LongTermMemoryProperties properties,
                                                       MemoryCaptureOutboxMapper outboxMapper) {
        return new LongTermMemoryRuntimeImpl(mem0Gateway, properties, outboxMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "memory.long-term", name = "enabled", havingValue = "false", matchIfMissing = true)
    public LongTermMemoryRuntime noopLongTermMemoryRuntime() {
        return new NoopLongTermMemoryRuntime();
    }

    @Bean
    @ConditionalOnProperty(prefix = "memory.long-term", name = "enabled", havingValue = "true")
    public UserMemoryCatalog userMemoryCatalog(Mem0Gateway mem0Gateway,
                                               LongTermMemoryRecordMapper recordMapper,
                                               MemoryOperationMapper operationMapper) {
        return new UserMemoryCatalogImpl(mem0Gateway, recordMapper, operationMapper);
    }

    @Bean
    @ConditionalOnProperty(prefix = "memory.long-term", name = "enabled", havingValue = "false", matchIfMissing = true)
    public UserMemoryCatalog disabledUserMemoryCatalog() {
        return new DisabledUserMemoryCatalog();
    }

    /** standard-chat 装配：长期记忆 + 知识库（依赖 promptId）。 */
    @Bean
    public ConversationContextAugmentor standardChatContextAugmentor(
            LongTermMemoryRuntime longTermMemoryRuntime,
            AgentMemoryPolicyCatalog policyCatalog,
            RetrievalAugmentor knowledgeRetrievalAugmentor) {
        LongTermMemoryContentRetriever memoryRetriever = new LongTermMemoryContentRetriever(
                longTermMemoryRuntime, policyCatalog, ChatAgentIds.STANDARD_CHAT);
        return new ConversationContextAugmentor(memoryRetriever, knowledgeRetrievalAugmentor);
    }

    /** enabled=true 且 URL/API key/contract 不完整时启动 fail-fast。 */
    private static void validateOrFailFast(LongTermMemoryProperties properties) {
        LongTermMemoryProperties.Mem0 mem0 = properties.getMem0();
        if (isBlank(mem0.getBaseUrl())) {
            throw new IllegalStateException("memory.long-term.mem0.base-url is required when long-term memory is enabled");
        }
        if (isBlank(mem0.getApiKey())) {
            throw new IllegalStateException("memory.long-term.mem0.api-key is required when long-term memory is enabled");
        }
        if (isBlank(mem0.getContractVersion()) || isBlank(mem0.getOpenapiSha256())) {
            throw new IllegalStateException(
                    "memory.long-term.mem0.contract-version and openapi-sha256 are required when long-term memory is enabled; "
                            + "floating Mem0 versions are not allowed");
        }
        log.info("Long-term memory enabled with pinned Mem0 contract version={}", mem0.getContractVersion());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
