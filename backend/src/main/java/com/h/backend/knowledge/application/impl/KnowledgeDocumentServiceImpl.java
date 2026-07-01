package com.h.backend.knowledge.application.impl;

import com.h.backend.common.exception.BusinessException;
import com.h.backend.knowledge.interfaces.dto.KnowledgeDocumentDto;
import com.h.backend.knowledge.infrastructure.persistence.entity.KnowledgeDocumentEntity;
import com.h.backend.knowledge.infrastructure.persistence.mapper.KnowledgeDocumentMapper;
import com.h.backend.knowledge.application.KnowledgeDocumentService;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class KnowledgeDocumentServiceImpl implements KnowledgeDocumentService {

    private final KnowledgeDocumentMapper mapper;

    public KnowledgeDocumentServiceImpl(KnowledgeDocumentMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public Long create(Long userId, Long promptId, String fileName, String sourceType,
                       String fileType, Long fileSize, String contentHash) {
        KnowledgeDocumentEntity doc = new KnowledgeDocumentEntity();
        doc.setUserId(userId);
        doc.setPromptId(promptId);
        doc.setFileName(fileName);
        doc.setSourceType(sourceType);
        doc.setFileType(fileType);
        doc.setFileSize(fileSize);
        doc.setContentHash(contentHash);
        doc.setStatus("PROCESSING");
        doc.setCreatedAt(LocalDateTime.now());
        doc.setUpdatedAt(LocalDateTime.now());
        mapper.insert(doc);
        return doc.getId();
    }

    @Override
    public void markCompleted(Long docId, int charCount, int segmentCount) {
        KnowledgeDocumentEntity doc = mapper.selectById(docId);
        if (doc == null) {
            return;
        }
        doc.setStatus("COMPLETED");
        doc.setCharCount(charCount);
        doc.setSegmentCount(segmentCount);
        doc.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(doc);
    }

    @Override
    public void markFailed(Long docId, String errorMsg) {
        KnowledgeDocumentEntity doc = mapper.selectById(docId);
        if (doc == null) {
            return;
        }
        doc.setStatus("FAILED");
        doc.setErrorMsg(errorMsg);
        doc.setUpdatedAt(LocalDateTime.now());
        mapper.updateById(doc);
    }

    @Override
    public List<KnowledgeDocumentDto> list(Long userId, Long promptId) {
        return mapper.selectByUserAndPrompt(userId, promptId).stream()
                .map(d -> new KnowledgeDocumentDto(
                        d.getId(), d.getFileName(), d.getSourceType(), d.getFileType(),
                        d.getFileSize(), d.getCharCount(), d.getSegmentCount(),
                        d.getStatus(), d.getErrorMsg(), d.getCreatedAt()))
                .toList();
    }

    @Override
    public KnowledgeDocumentEntity requireOwned(Long userId, Long docId) {
        KnowledgeDocumentEntity doc = mapper.selectById(docId);
        if (doc == null) {
            throw new BusinessException(40400, "文档不存在");
        }
        if (!doc.getUserId().equals(userId)) {
            throw new BusinessException(40300, "无权操作该文档");
        }
        return doc;
    }

    @Override
    public void delete(Long docId) {
        mapper.deleteById(docId);
    }
}
