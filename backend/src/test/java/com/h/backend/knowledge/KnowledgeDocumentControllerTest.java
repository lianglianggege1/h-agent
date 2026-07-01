package com.h.backend.knowledge;

import com.h.backend.knowledge.interfaces.web.KnowledgeDocumentController;
import com.h.backend.knowledge.application.KnowledgeDocumentService;
import com.h.backend.knowledge.application.KnowledgeIngestService;
import com.h.backend.knowledge.infrastructure.persistence.mapper.KnowledgeSegmentMapper;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class KnowledgeDocumentControllerTest {

    private final KnowledgeIngestService ingestService = Mockito.mock(KnowledgeIngestService.class);
    private final KnowledgeDocumentService documentService = Mockito.mock(KnowledgeDocumentService.class);
    private final KnowledgeSegmentMapper segmentMapper = Mockito.mock(KnowledgeSegmentMapper.class);

    private final KnowledgeDocumentController controller =
            new KnowledgeDocumentController(ingestService, documentService, segmentMapper);

    @Test
    void deleteShouldCheckOwnershipThenRemoveVectorsAndMetadata() {
        var principal = new com.h.backend.shared.infrastructure.security.AuthUserPrincipal(1L, "a@b.com", "USER");
        var doc = new com.h.backend.knowledge.infrastructure.persistence.entity.KnowledgeDocumentEntity();
        doc.setId(5L);
        doc.setUserId(1L);
        Mockito.when(documentService.requireOwned(1L, 5L)).thenReturn(doc);

        var resp = controller.delete(principal, 5L);

        assertEquals(0, resp.code());
        Mockito.verify(documentService).requireOwned(1L, 5L);
        Mockito.verify(ingestService).removeVectors(5L);
        Mockito.verify(documentService).delete(5L);
    }

    @Test
    void manualShouldDelegateToIngestService() {
        var principal = new com.h.backend.shared.infrastructure.security.AuthUserPrincipal(1L, "a@b.com", "USER");
        Mockito.when(ingestService.ingestManual(1L, 2L, "标题", "内容")).thenReturn(7L);

        var req = new com.h.backend.knowledge.interfaces.dto.ManualInputRequest(2L, "标题", "内容");
        var resp = controller.manual(principal, req);

        assertEquals(0, resp.code());
        assertNotNull(resp.data());
        assertEquals(7L, resp.data());
    }
}
