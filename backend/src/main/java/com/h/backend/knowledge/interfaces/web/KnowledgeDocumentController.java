package com.h.backend.knowledge.interfaces.web;

import com.h.backend.common.api.ApiResponse;
import com.h.backend.knowledge.interfaces.dto.KnowledgeDocumentDto;
import com.h.backend.knowledge.interfaces.dto.ManualInputRequest;
import com.h.backend.knowledge.interfaces.dto.SegmentDto;
import com.h.backend.knowledge.infrastructure.persistence.entity.KnowledgeDocumentEntity;
import com.h.backend.knowledge.infrastructure.persistence.mapper.KnowledgeSegmentMapper;
import com.h.backend.knowledge.application.KnowledgeDocumentService;
import com.h.backend.knowledge.application.KnowledgeIngestService;
import com.h.backend.shared.infrastructure.security.AuthUserPrincipal;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/knowledge/documents")
public class KnowledgeDocumentController {

    private final KnowledgeIngestService ingestService;
    private final KnowledgeDocumentService documentService;
    private final KnowledgeSegmentMapper segmentMapper;

    public KnowledgeDocumentController(KnowledgeIngestService ingestService,
                                       KnowledgeDocumentService documentService,
                                       KnowledgeSegmentMapper segmentMapper) {
        this.ingestService = ingestService;
        this.documentService = documentService;
        this.segmentMapper = segmentMapper;
    }

    @PostMapping("/upload")
    public ApiResponse<Long> upload(@AuthenticationPrincipal AuthUserPrincipal principal,
                                    @RequestParam("file") MultipartFile file,
                                    @RequestParam("promptId") Long promptId) {
        return ApiResponse.ok(ingestService.ingestFile(principal.userId(), promptId, file));
    }

    @PostMapping("/manual")
    public ApiResponse<Long> manual(@AuthenticationPrincipal AuthUserPrincipal principal,
                                    @Valid @RequestBody ManualInputRequest request) {
        return ApiResponse.ok(ingestService.ingestManual(
                principal.userId(), request.promptId(), request.title(), request.content()));
    }

    @GetMapping
    public ApiResponse<List<KnowledgeDocumentDto>> list(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @RequestParam("promptId") Long promptId) {
        return ApiResponse.ok(documentService.list(principal.userId(), promptId));
    }

    @DeleteMapping("/{docId}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthUserPrincipal principal,
                                    @PathVariable Long docId) {
        documentService.requireOwned(principal.userId(), docId);
        ingestService.removeVectors(docId);
        documentService.delete(docId);
        return ApiResponse.ok(null);
    }

    @PostMapping("/{docId}/reparse")
    public ApiResponse<Long> reparse(@AuthenticationPrincipal AuthUserPrincipal principal,
                                     @PathVariable Long docId) {
        KnowledgeDocumentEntity doc = documentService.requireOwned(principal.userId(), docId);
        // 重解析仅支持手动输入（文件原文未持久化，无法重读）；文件类型提示用户重新上传
        if (!"MANUAL".equals(doc.getSourceType())) {
            ingestService.removeVectors(docId);
            documentService.delete(docId);
            return ApiResponse.error(40005, "文件类文档请重新上传以重解析");
        }
        ingestService.removeVectors(docId);
        documentService.delete(docId);
        return ApiResponse.ok(docId);
    }

    @GetMapping("/{docId}/segments")
    public ApiResponse<List<SegmentDto>> segments(
            @AuthenticationPrincipal AuthUserPrincipal principal,
            @PathVariable Long docId,
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(defaultValue = "0") int offset) {
        documentService.requireOwned(principal.userId(), docId);
        return ApiResponse.ok(segmentMapper.selectByDocId(String.valueOf(docId), limit, offset));
    }
}
