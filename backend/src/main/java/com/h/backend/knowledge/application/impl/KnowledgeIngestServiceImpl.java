package com.h.backend.knowledge.application.impl;

import com.h.backend.common.exception.BusinessException;
import com.h.backend.knowledge.infrastructure.config.KnowledgeProperties;
import com.h.backend.knowledge.application.KnowledgeDocumentService;
import com.h.backend.knowledge.application.KnowledgeIngestService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.DocumentSplitter;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.parser.apache.tika.ApacheTikaDocumentParser;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Slf4j
@Service
public class KnowledgeIngestServiceImpl implements KnowledgeIngestService {

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final KnowledgeDocumentService documentService;
    private final KnowledgeProperties props;

    public KnowledgeIngestServiceImpl(EmbeddingStore<TextSegment> embeddingStore,
                                      EmbeddingModel embeddingModel,
                                      KnowledgeDocumentService documentService,
                                      KnowledgeProperties props) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.documentService = documentService;
        this.props = props;
    }

    @Override
    public boolean isAllowedType(String fileName) {
        if (fileName == null) {
            return false;
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return false;
        }
        String ext = fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
        return props.getUpload().getAllowedTypes().contains(ext);
    }

    @Override
    @Transactional
    public Long ingestFile(Long userId, Long promptId, MultipartFile file) {
        String fileName = file.getOriginalFilename();
        if (!isAllowedType(fileName)) {
            throw new BusinessException(40001, "不支持的文件类型：" + fileName);
        }
        String ext = fileName.substring(fileName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        Long docId = documentService.create(userId, promptId, fileName, "FILE",
                ext, file.getSize(), null);
        try (InputStream in = file.getInputStream()) {
            Document document = new ApacheTikaDocumentParser().parse(in);
            ingestDocument(docId, userId, promptId, fileName, document);
            return docId;
        } catch (IOException | RuntimeException ex) {
            log.warn("文档解析入库失败 docId={}", docId, ex);
            documentService.markFailed(docId, truncate(ex.getMessage()));
            throw new BusinessException(40002, "文档解析失败：" + truncate(ex.getMessage()));
        }
    }

    @Override
    @Transactional
    public Long ingestManual(Long userId, Long promptId, String title, String content) {
        Long docId = documentService.create(userId, promptId, title, "MANUAL",
                "txt", (long) content.length(), null);
        try {
            ingestDocument(docId, userId, promptId, title, Document.from(content));
            return docId;
        } catch (RuntimeException ex) {
            log.warn("手动输入入库失败 docId={}", docId, ex);
            documentService.markFailed(docId, truncate(ex.getMessage()));
            throw new BusinessException(40002, "入库失败：" + truncate(ex.getMessage()));
        }
    }

    private void ingestDocument(Long docId, Long userId, Long promptId,
                                String fileName, Document document) {
        String text = document.text();
        if (text == null || text.isBlank()) {
            documentService.markFailed(docId, "解析后内容为空");
            throw new BusinessException(40003, "解析后内容为空");
        }
        DocumentSplitter splitter = DocumentSplitters.recursive(
                props.getSplit().getChunkSize(), props.getSplit().getChunkOverlap());
        List<TextSegment> segments = splitter.split(document);
        for (TextSegment seg : segments) {
            Metadata md = seg.metadata();
            md.put("promptId", String.valueOf(promptId));
            md.put("docId", String.valueOf(docId));
            md.put("userId", String.valueOf(userId));
            md.put("fileName", fileName);
        }
        List<Embedding> embeddings = embeddingModel.embedAll(segments).content();
        embeddingStore.addAll(embeddings, segments);
        documentService.markCompleted(docId, text.length(), segments.size());
    }

    @Override
    public void removeVectors(Long docId) {
        embeddingStore.removeAll(metadataKey("docId").isEqualTo(String.valueOf(docId)));
    }

    private String truncate(String msg) {
        if (msg == null) {
            return "未知错误";
        }
        return msg.length() > 500 ? msg.substring(0, 500) : msg;
    }
}
