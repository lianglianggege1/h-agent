package com.h.backend.common.exception;

import com.h.backend.chat.infrastructure.storage.ResourceStorageException;
import com.h.backend.common.api.ApiResponse;
import com.h.backend.skill.domain.SkillPlatformErrorKind;
import com.h.backend.skill.domain.SkillPlatformException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusinessException(BusinessException ex) {
        HttpStatus status = ex.getCode() >= 40100 && ex.getCode() < 40200
                ? HttpStatus.UNAUTHORIZED
                : ex.getCode() == 40404 ? HttpStatus.NOT_FOUND : HttpStatus.BAD_REQUEST;
        return ResponseEntity.status(status).body(ApiResponse.error(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidationException(MethodArgumentNotValidException ex) {
        return ResponseEntity.badRequest().body(ApiResponse.error(40001, "参数错误"));
    }

    /**
     * 四类存储错误的全局 HTTP 映射（审查修复第 1 项 / 计划 §4.5）：
     * NOT_FOUND 404 / SIZE_LIMIT 413 / UNAVAILABLE 503 / IO_ERROR 500。
     *
     * <p>预览/下载路径的 NOT_FOUND 已在 ChatResourceServiceImpl 转为
     * BusinessException 40404（用户可见语义），本分支是全局兜底；
     * 响应体沿用 ApiResponse.error 形状，消息使用异常自带的脱敏安全文案，
     * cause 链不并入响应。
     */
    @ExceptionHandler(ResourceStorageException.class)
    public ResponseEntity<ApiResponse<Void>> handleResourceStorageException(ResourceStorageException ex) {
        return ResponseEntity.status(ex.kind().httpStatus())
                .body(ApiResponse.error(ex.kind().httpStatus(), ex.getMessage()));
    }

    /**
     * Skill 平台错误的全局 HTTP 映射（设计 §11.1）：
     * 40404→404、400xx→400、409xx→409、503xx→503、500xx→500。
     * 消息使用异常自带的脱敏文案，cause 链不并入响应。
     */
    @ExceptionHandler(SkillPlatformException.class)
    public ResponseEntity<ApiResponse<Void>> handleSkillPlatformException(SkillPlatformException ex) {
        return ResponseEntity.status(httpStatusOf(ex.kind()))
                .body(ApiResponse.error(ex.kind().code(), ex.getMessage()));
    }

    private HttpStatus httpStatusOf(SkillPlatformErrorKind kind) {
        int code = kind.code();
        if (code == 40404) {
            return HttpStatus.NOT_FOUND;
        }
        if (code >= 40900 && code < 41000) {
            return HttpStatus.CONFLICT;
        }
        if (code >= 40000 && code < 41000) {
            return HttpStatus.BAD_REQUEST;
        }
        if (code >= 50300 && code < 50400) {
            return HttpStatus.SERVICE_UNAVAILABLE;
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }
}
