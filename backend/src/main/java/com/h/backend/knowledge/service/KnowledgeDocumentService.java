package com.h.backend.knowledge.service;

import com.h.backend.knowledge.dto.KnowledgeDocumentDto;
import com.h.backend.knowledge.entity.KnowledgeDocumentEntity;

import java.util.List;

public interface KnowledgeDocumentService {

    /** 创建元数据记录（status=PROCESSING），返回 docId */
    Long create(Long userId, Long promptId, String fileName, String sourceType,
                String fileType, Long fileSize, String contentHash);

    void markCompleted(Long docId, int charCount, int segmentCount);

    void markFailed(Long docId, String errorMsg);

    List<KnowledgeDocumentDto> list(Long userId, Long promptId);

    /** 校验文档归属当前用户，否则抛 40300；返回实体 */
    KnowledgeDocumentEntity requireOwned(Long userId, Long docId);

    void delete(Long docId);
}
