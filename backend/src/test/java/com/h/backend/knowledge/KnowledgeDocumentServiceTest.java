package com.h.backend.knowledge;

import com.h.backend.common.exception.BusinessException;
import com.h.backend.knowledge.entity.KnowledgeDocumentEntity;
import com.h.backend.knowledge.mapper.KnowledgeDocumentMapper;
import com.h.backend.knowledge.service.impl.KnowledgeDocumentServiceImpl;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class KnowledgeDocumentServiceTest {

    private final KnowledgeDocumentMapper mapper = Mockito.mock(KnowledgeDocumentMapper.class);
    private final KnowledgeDocumentServiceImpl service = new KnowledgeDocumentServiceImpl(mapper);

    @Test
    void requireOwnedShouldThrowWhenUserMismatch() {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setId(10L);
        doc.setUserId(2L);
        Mockito.when(mapper.selectById(10L)).thenReturn(doc);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.requireOwned(1L, 10L));
        assertEquals(40300, ex.getCode());
    }

    @Test
    void requireOwnedShouldReturnWhenOwner() {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setId(10L);
        doc.setUserId(1L);
        Mockito.when(mapper.selectById(10L)).thenReturn(doc);

        assertEquals(10L, service.requireOwned(1L, 10L).getId());
    }
}
