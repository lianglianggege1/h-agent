package com.h.backend.knowledge.application;

import org.springframework.web.multipart.MultipartFile;

public interface KnowledgeIngestService {

    /** 上传文件：解析→切片→嵌入→入库，返回 docId */
    Long ingestFile(Long userId, MultipartFile file);

    /** 手动输入文本入库，返回 docId */
    Long ingestManual(Long userId, String title, String content);

    /** 删除文档对应的全部向量（按 docId 过滤） */
    void removeVectors(Long docId);

    /** 文件名后缀是否在白名单内 */
    boolean isAllowedType(String fileName);
}
