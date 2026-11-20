package com.h.backend.knowledge;

import com.h.backend.knowledge.infrastructure.persistence.entity.KnowledgeDocumentEntity;
import com.h.backend.knowledge.infrastructure.persistence.mapper.KnowledgeDocumentMapper;
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
        // 真实 PG 中存在用户数据：用一次性 userId 隔离，避免断言受残留数据影响。
        long userId = System.currentTimeMillis();
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setUserId(userId);

        doc.setFileName("test.md");
        doc.setSourceType("FILE");
        doc.setFileType("md");
        doc.setStatus("PROCESSING");
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        mapper.insert(doc);
        assertNotNull(doc.getId());

        try {
            List<KnowledgeDocumentEntity> found = mapper.selectByUser(userId);
            assertEquals(1, found.size());
            assertEquals("test.md", found.get(0).getFileName());
        } finally {
            mapper.deleteById(doc.getId());
        }
    }
}
