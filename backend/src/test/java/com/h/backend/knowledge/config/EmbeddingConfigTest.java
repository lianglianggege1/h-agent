package com.h.backend.knowledge.infrastructure.config;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.AugmentationResult;
import dev.langchain4j.rag.RetrievalAugmentor;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmbeddingConfigTest {

    @Test
    void retrievalAugmentorShouldIgnoreEmptyRagResult() {
        EmbeddingConfig config = new EmbeddingConfig();
        ChatModel routingModel = new ChatModel() {
            @Override
            public String chat(String userMessage) {
                if (userMessage.contains("determine the most suitable data source")) {
                    return "2";
                }
                return "没有匹配资料";
            }
        };
        ContentRetriever emptyRetriever = query -> List.of();
        RetrievalAugmentor augmentor = config.knowledgeRetrievalAugmentor(routingModel, emptyRetriever);

        UserMessage message = UserMessage.from("搜不到资料的问题");
        Metadata metadata = Metadata.from(message, "1:2:test-session", List.of());
        AugmentationRequest request = new AugmentationRequest(message, metadata);

        AugmentationResult result = assertDoesNotThrow(() -> augmentor.augment(request));
        assertTrue(result.contents().isEmpty());
    }

    @Test
    void retrievalAugmentorShouldKeepSuccessfulRagResult() {
        EmbeddingConfig config = new EmbeddingConfig();
        ChatModel routingModel = new ChatModel() {
            @Override
            public String chat(String userMessage) {
                if (userMessage.contains("determine the most suitable data source")) {
                    return "1";
                }
                return "命中问题";
            }
        };
        ContentRetriever hitRetriever = query -> List.of(Content.from("命中内容"));
        RetrievalAugmentor augmentor = config.knowledgeRetrievalAugmentor(routingModel, hitRetriever);

        UserMessage message = UserMessage.from("能命中资料的问题");
        Metadata metadata = Metadata.from(message, "1:2:test-session", List.of());
        AugmentationRequest request = new AugmentationRequest(message, metadata);

        AugmentationResult result = augmentor.augment(request);

        assertEquals(1, result.contents().size());
        assertEquals("命中内容", result.contents().get(0).textSegment().text());
    }

    @Test
    void contentRetrieverShouldFailClosedWhenPromptScopeIsMissing() {
        CapturingEmbeddingStore store = new CapturingEmbeddingStore();
        EmbeddingModel model = textSegments -> Response.from(
                textSegments.stream().map(ignored -> new Embedding(new float[]{0.1f, 0.2f})).toList()
        );

        ContentRetriever retriever = new EmbeddingConfig()
                .knowledgeContentRetriever(store, model, new KnowledgeProperties());
        retriever.retrieve(Query.from("没有 prompt 作用域的问题"));

        assertNotNull(store.lastRequest.filter(), "缺失 promptId 时不能退化为全库检索");
    }

    private static final class CapturingEmbeddingStore implements EmbeddingStore<TextSegment> {

        private EmbeddingSearchRequest lastRequest;

        @Override
        public String add(Embedding embedding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void add(String id, Embedding embedding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String add(Embedding embedding, TextSegment embedded) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void removeAll(Collection<String> ids) {
            throw new UnsupportedOperationException();
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            this.lastRequest = request;
            return new EmbeddingSearchResult<>(List.of());
        }
    }
}
