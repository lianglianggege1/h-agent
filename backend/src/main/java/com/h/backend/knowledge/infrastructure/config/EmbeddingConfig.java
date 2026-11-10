package com.h.backend.knowledge.infrastructure.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.bgesmallzhv15q.BgeSmallZhV15QuantizedEmbeddingModel;
import dev.langchain4j.rag.DefaultRetrievalAugmentor;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Configuration
public class EmbeddingConfig {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingConfig.class);
    private static final String MISSING_USER_SCOPE = "__missing_user_scope__";
    private static final AtomicBoolean missingUserScopeWarningLogged = new AtomicBoolean(false);

    static void resetMissingUserScopeWarningForTests() {
        missingUserScopeWarningLogged.set(false);
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
                        return missingUserScopeFilter();
                    }
                    Object userId = query.metadata().invocationParameters()
                            .get(com.h.backend.memory.domain.MemoryInvocationContext.INVOCATION_KEY);
                    if (!(userId instanceof com.h.backend.memory.domain.MemoryInvocationContext context)
                            || context.userId() == null) {
                        return missingUserScopeFilter();
                    }
                    return metadataKey("userId").isEqualTo(String.valueOf(context.userId()));
                })
                .build();
    }

    /** Compatibility entry point, not attached to any production Agent. No extra LLM calls. */
    @Bean
    public RetrievalAugmentor knowledgeRetrievalAugmentor(ContentRetriever knowledgeContentRetriever) {
        return new SafeRetrievalAugmentor(DefaultRetrievalAugmentor.builder()
                .contentRetriever(knowledgeContentRetriever).build());
    }

    private static Filter missingUserScopeFilter() {
        if (missingUserScopeWarningLogged.compareAndSet(false, true)) {
            log.warn("RAG user identity is missing; skipping knowledge retrieval");
        }
        return metadataKey("userId").isEqualTo(MISSING_USER_SCOPE);
    }
}
