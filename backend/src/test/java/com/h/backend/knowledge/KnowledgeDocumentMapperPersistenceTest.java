package com.h.backend.knowledge;

import com.h.backend.knowledge.entity.KnowledgeDocumentEntity;
import com.h.backend.knowledge.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class KnowledgeDocumentMapperPersistenceTest {

    @Autowired
    private KnowledgeDocumentMapper mapper;

    @Test
    void shouldInsertAndQueryByUserAndPrompt() {
        long promptId = System.currentTimeMillis();
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setUserId(1L);
        doc.setPromptId(promptId);
        doc.setFileName("test.md");
        doc.setSourceType("FILE");
        doc.setFileType("md");
        doc.setStatus("PROCESSING");
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        mapper.insert(doc);
        assertNotNull(doc.getId());

        List<KnowledgeDocumentEntity> found = mapper.selectByUserAndPrompt(1L, promptId);
        assertEquals(1, found.size());
        assertEquals("test.md", found.get(0).getFileName());
    }
}
