package com.h.backend.knowledge;

import com.h.backend.knowledge.infrastructure.config.KnowledgeProperties;
import com.h.backend.knowledge.application.KnowledgeDocumentService;
import com.h.backend.knowledge.application.impl.KnowledgeIngestServiceImpl;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;

class KnowledgeIngestServiceTest {

    @SuppressWarnings("unchecked")
    private final EmbeddingStore<TextSegment> store = Mockito.mock(EmbeddingStore.class);
    private final EmbeddingModel model = Mockito.mock(EmbeddingModel.class);
    private final KnowledgeDocumentService docService = Mockito.mock(KnowledgeDocumentService.class);
    private final KnowledgeProperties props = new KnowledgeProperties();

    private final KnowledgeIngestServiceImpl service =
            new KnowledgeIngestServiceImpl(store, model, docService, props);

    @Test
    void manualIngestShouldSplitTagMetadataAndStore() {
        Mockito.when(docService.create(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(99L);
        Mockito.when(model.embedAll(anyList()))
                .thenAnswer(inv -> {
                    List<TextSegment> segs = inv.getArgument(0);
                    List<Embedding> embs = segs.stream()
                            .map(s -> new Embedding(new float[]{0.1f, 0.2f})).toList();
                    return Response.from(embs);
                });

        service.ingestManual(1L, 2L, "标题", "这是一段用于测试的中文知识内容。".repeat(50));

        ArgumentCaptor<List<TextSegment>> captor = ArgumentCaptor.forClass(List.class);
        Mockito.verify(store).addAll(anyList(), captor.capture());
        List<TextSegment> stored = captor.getValue();
        assertFalse(stored.isEmpty());
        TextSegment first = stored.get(0);
        assertEquals("2", first.metadata().getString("promptId"));
        assertEquals("99", first.metadata().getString("docId"));
        assertEquals("1", first.metadata().getString("userId"));
        Mockito.verify(docService).markCompleted(Mockito.eq(99L), Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    void isAllowedTypeShouldRejectUnknownExtension() {
        assertTrue(service.isAllowedType("report.docx"));
        assertTrue(service.isAllowedType("notes.MD"));
        assertFalse(service.isAllowedType("malware.exe"));
        assertFalse(service.isAllowedType("noext"));
    }
}
