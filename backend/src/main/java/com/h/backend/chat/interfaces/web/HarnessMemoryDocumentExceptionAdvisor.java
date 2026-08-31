package com.h.backend.chat.interfaces.web;

import com.h.backend.chat.domain.memory.HarnessMemoryDocumentException;
import com.h.backend.common.api.ApiResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 用户长期记忆错误映射（409/413/500/503 与安全文案）；存储细节与正文不进入响应。
 */
@RestControllerAdvice
public class HarnessMemoryDocumentExceptionAdvisor {

    @ExceptionHandler(HarnessMemoryDocumentException.class)
    public ResponseEntity<ApiResponse<Void>> handle(HarnessMemoryDocumentException ex) {
        return ResponseEntity.status(httpStatusOf(ex.kind()))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(ApiResponse.error(ex.kind().code(), ex.getMessage()));
    }

    private static HttpStatus httpStatusOf(HarnessMemoryDocumentException.Kind kind) {
        return switch (kind) {
            case REVISION_CONFLICT -> HttpStatus.CONFLICT;
            case CONTENT_TOO_LARGE -> HttpStatus.PAYLOAD_TOO_LARGE;
            case CONTENT_CORRUPT -> HttpStatus.INTERNAL_SERVER_ERROR;
            case STORE_UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
