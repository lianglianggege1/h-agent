package com.h.backend.memory.infrastructure.langchain4j;

import com.h.backend.memory.application.LongTermMemoryRuntime;
import com.h.backend.memory.domain.AgentMemoryPolicyCatalog;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.RetrievalAugmentor;
import org.springframework.stereotype.Component;

/**
 * 为 Agentic 叶子 Agent 构建带稳定逻辑 Agent ID 绑定的 RetrievalAugmentor。
 * 叶子召回时用构建时绑定的叶子 ID 替换身份中的 agent 字段。
 */
@Component
public class ConversationContextAugmentorFactory {

    private final LongTermMemoryRuntime runtime;
    private final AgentMemoryPolicyCatalog policyCatalog;

    public ConversationContextAugmentorFactory(LongTermMemoryRuntime runtime,
                                               AgentMemoryPolicyCatalog policyCatalog) {
        this.runtime = runtime;
        this.policyCatalog = policyCatalog;
    }

    /** 仅长期记忆，无知识库（一般领域叶子 Agent）。 */
    public ConversationContextAugmentor memoryOnly(String logicalAgentId) {
        return new ConversationContextAugmentor(
                new LongTermMemoryContentRetriever(runtime, policyCatalog, logicalAgentId),
                passthroughAugmentor()
        );
    }

    /** 长期记忆 + 知识库（显式绑定知识的领域 Agent）。 */
    public ConversationContextAugmentor withKnowledge(String logicalAgentId,
                                                       RetrievalAugmentor knowledgeAugmentor) {
        return new ConversationContextAugmentor(
                new LongTermMemoryContentRetriever(runtime, policyCatalog, logicalAgentId),
                knowledgeAugmentor
        );
    }

    private static RetrievalAugmentor passthroughAugmentor() {
        return new RetrievalAugmentor() {
            @Override
            public AugmentationResult augment(AugmentationRequest request) {
                return AugmentationResult.builder()
                        .chatMessage(request.chatMessage())
                        .contents(java.util.List.of())
                        .build();
            }
        };
    }
}
