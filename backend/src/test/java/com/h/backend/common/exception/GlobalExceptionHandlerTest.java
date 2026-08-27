package com.h.backend.common.exception;

import com.h.backend.chat.infrastructure.storage.ResourceStorageErrorKind;
import com.h.backend.chat.infrastructure.storage.ResourceStorageException;
import com.h.backend.common.api.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 四类存储错误的全局 HTTP 映射（审查修复第 1 项 / 计划 §4.5）。
 *
 * <p>ChatResourceServiceImpl 已把预览/下载路径的 NOT_FOUND 转为
 * BusinessException 40404（用户可见语义），Service 层测试已锁定该行为；
 * 本 handler 是全局兜底：任何未在业务层转换的 ResourceStorageException
 * 按 {@code kind.httpStatus()} 映射（NOT_FOUND 404 / SIZE_LIMIT 413 /
 * UNAVAILABLE 503 / IO_ERROR 500），响应体沿用 ApiResponse.error 形状
 * （与 BusinessException 分支一致），消息使用异常自带的脱敏安全文案。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void sizeLimitStorageErrorMapsTo413() {
        ResourceStorageException exception = new ResourceStorageException(
                ResourceStorageErrorKind.SIZE_LIMIT, "资源大小超过存储上限");

        ResponseEntity<ApiResponse<Void>> response = handler.handleResourceStorageException(exception);

        // 413 枚举在 Spring 6+ 有 PAYLOAD_TOO_LARGE/CONTENT_TOO_LARGE 双名，
        // 用数值锁定 kind.httpStatus() 的 HTTP 语义。
        assertEquals(413, response.getStatusCode().value());
        assertErrorBody(response, 413, "资源大小超过存储上限");
    }

    @Test
    void unavailableStorageErrorMapsTo503() {
        ResourceStorageException exception = new ResourceStorageException(
                ResourceStorageErrorKind.UNAVAILABLE, "存储服务暂时不可用");

        ResponseEntity<ApiResponse<Void>> response = handler.handleResourceStorageException(exception);

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode());
        assertErrorBody(response, 503, "存储服务暂时不可用");
    }

    @Test
    void ioErrorStorageErrorMapsTo500() {
        ResourceStorageException exception = new ResourceStorageException(
                ResourceStorageErrorKind.IO_ERROR, "资源存储读写失败");

        ResponseEntity<ApiResponse<Void>> response = handler.handleResourceStorageException(exception);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertErrorBody(response, 500, "资源存储读写失败");
    }

    @Test
    void notFoundStorageErrorMapsTo404AsGlobalFallback() {
        // 预览/下载路径的 NOT_FOUND 已在 ChatResourceServiceImpl 转为
        // BusinessException 40404；该分支只兜底未被业务层转换的 NOT_FOUND
        // （如未来新增的读取入口），两层不冲突。
        ResourceStorageException exception = new ResourceStorageException(
                ResourceStorageErrorKind.NOT_FOUND, "资源不存在或已被删除");

        ResponseEntity<ApiResponse<Void>> response = handler.handleResourceStorageException(exception);

        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        assertErrorBody(response, 404, "资源不存在或已被删除");
    }

    @Test
    void causeChainIsNeverExposedInResponseBody() {
        // 消息必须是异常的脱敏安全文案；cause 中的原始异常
        // （可能含 endpoint/bucket/key）不得并入响应体。
        ResourceStorageException exception = new ResourceStorageException(
                ResourceStorageErrorKind.IO_ERROR, "资源存储读写失败",
                new RuntimeException("secret http://minio:9000/resources/v1/files/key"));

        ResponseEntity<ApiResponse<Void>> response = handler.handleResourceStorageException(exception);

        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertErrorBody(response, 500, "资源存储读写失败");
    }

    private void assertErrorBody(
            ResponseEntity<ApiResponse<Void>> response, int expectedCode, String expectedMessage) {
        assertNotNull(response.getBody());
        assertEquals(expectedCode, response.getBody().code());
        assertEquals(expectedMessage, response.getBody().message());
        assertNull(response.getBody().data());
    }
}
