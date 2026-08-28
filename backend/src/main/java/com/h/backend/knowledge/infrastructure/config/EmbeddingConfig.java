package com.h.backend.knowledge.infrastructure.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.router.LanguageModelQueryRouter;
import dev.langchain4j.rag.query.router.LanguageModelQueryRouter.FallbackStrategy;
import dev.langchain4j.rag.query.router.QueryRouter;
import dev.langchain4j.rag.query.transformer.ExpandingQueryTransformer;
import dev.langchain4j.rag.query.transformer.QueryTransformer;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.pgvector.DefaultMetadataStorageConfig;
import dev.langchain4j.store.embedding.pgvector.MetadataStorageMode;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);
    private static final String MISSING_PROMPT_SCOPE = "__missing_prompt_scope__";
    private static final AtomicBoolean missingPromptScopeWarningLogged = new AtomicBoolean(false);

    static void resetMissingPromptScopeWarningForTests() {
        missingPromptScopeWarningLogged.set(false);
    }

    @Bean
    public EmbeddingModel embeddingModel() {
        return new BgeSmallZhV15QuantizedEmbeddingModel();
    }

    @Bean
    public EmbeddingStore<TextSegment> knowledgeEmbeddingStore(DataSource dataSource) {
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table("knowledge_embeddings")
                .dimension(512)
                .createTable(true)
                .metadataStorageConfig(DefaultMetadataStorageConfig.builder()
                        .storageMode(MetadataStorageMode.COMBINED_JSONB)
                        .columnDefinitions(List.of("metadata JSONB NULL"))
                        .build())
                .build();
    }

    @Bean
    public ContentRetriever knowledgeContentRetriever(
            EmbeddingStore<TextSegment> knowledgeEmbeddingStore,
            EmbeddingModel embeddingModel,
            KnowledgeProperties props) {
        return EmbeddingStoreContentRetriever.builder()
                .embeddingStore(knowledgeEmbeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(props.getRetriever().getMaxResults())
                .minScore(props.getRetriever().getMinScore())
                .dynamicFilter(query -> {
                    // 身份从服务端可信的 InvocationParameters 读取，缺失时返回空知识结果，不返回无过滤结果
                    if (query.metadata() == null || query.metadata().invocationParameters() == null) {
                        return missingPromptScopeFilter();
                    }
                    Object userId = query.metadata().invocationParameters()
                            .get(com.h.backend.memory.domain.MemoryInvocationContext.INVOCATION_KEY);
                    if (!(userId instanceof com.h.backend.memory.domain.MemoryInvocationContext context)
                            || context.userId() == null
                            || context.promptId() == null) {
                        return missingPromptScopeFilter();
                    }
                    // metadata.userId = authenticated userId AND metadata.promptId = resolved promptId
                    // promptId 以字符串存入 metadata，故按字符串过滤
                    return metadataKey("userId").isEqualTo(String.valueOf(context.userId()))
                            .and(metadataKey("promptId").isEqualTo(String.valueOf(context.promptId())));
                })
                .build();
    }

    @Bean
    public RetrievalAugmentor knowledgeRetrievalAugmentor(
            ChatModel chatModel,
            ContentRetriever knowledgeContentRetriever) {

        QueryTransformer queryTransformer = new ExpandingQueryTransformer(chatModel);

        Map<ContentRetriever, String> retrieverToDescription = Map.of(
                knowledgeContentRetriever,
                "用户上传的知识库文档，包含手册、资料等参考内容");
        QueryRouter queryRouter = LanguageModelQueryRouter.builder()
                .chatModel(chatModel)
                .retrieverToDescription(retrieverToDescription)
                .fallbackStrategy(FallbackStrategy.ROUTE_TO_ALL)
                .build();

        RetrievalAugmentor retrievalAugmentor = DefaultRetrievalAugmentor.builder()
                .queryTransformer(queryTransformer)
                .queryRouter(queryRouter)
                .build();
        return new SafeRetrievalAugmentor(retrievalAugmentor);
    }

    private static Filter missingPromptScopeFilter() {
        if (missingPromptScopeWarningLogged.compareAndSet(false, true)) {
            log.warn("RAG prompt scope is missing; skipping knowledge retrieval to avoid cross-prompt results");
        }
        return metadataKey("promptId").isEqualTo(MISSING_PROMPT_SCOPE);
    }
}
