package com.h.backend.knowledge;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class EmbeddingConfigContextTest {

    @Autowired
    private EmbeddingModel embeddingModel;
    @Autowired
    private EmbeddingStore<TextSegment> knowledgeEmbeddingStore;
    @Autowired
    private ContentRetriever knowledgeContentRetriever;
    @Autowired
    private RetrievalAugmentor knowledgeRetrievalAugmentor;

    @Test
    void beansShouldBeWired() {
        assertNotNull(embeddingModel);
        assertNotNull(knowledgeEmbeddingStore);
        assertNotNull(knowledgeContentRetriever);
        assertNotNull(knowledgeRetrievalAugmentor);
    }

    @Test
    void bgeEmbeddingDimensionIs512() {
        assertEquals(512, embeddingModel.embed("测试中文").content().dimension());
    }
}
