package com.h.backend.knowledge;

import com.h.backend.knowledge.service.KnowledgeIngestService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class KnowledgeRagIntegrationTest {

    @Autowired
    private KnowledgeIngestService ingestService;
    @Autowired
    private ContentRetriever knowledgeContentRetriever;

    private Query queryFor(String text, long userId, long promptId) {
        String memoryId = userId + ":" + promptId + ":sess-test";
        Metadata md = Metadata.from(UserMessage.from(text), memoryId, List.of());
        return Query.from(text, md);
    }

    @Test
    void shouldRetrieveOnlyWithinSamePrompt() {
        long promptA = System.currentTimeMillis();
        long promptB = promptA + 1;
        ingestService.ingestManual(1L, promptA, "向量数据库说明",
                "PgVector 是 PostgreSQL 的向量检索扩展，支持余弦相似度搜索。".repeat(10));

        List<Content> hitA = knowledgeContentRetriever.retrieve(
                queryFor("什么是 PgVector", 1L, promptA));
        assertFalse(hitA.isEmpty(), "同 promptId 应能检索到内容");

        List<Content> hitB = knowledgeContentRetriever.retrieve(
                queryFor("什么是 PgVector", 1L, promptB));
        assertTrue(hitB.isEmpty(), "不同 promptId 不应检索到内容（隔离）");
    }
}
