package com.h.backend.memory.infrastructure.mem0;

import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import java.net.SocketTimeoutException;

/**
 * Mem0 异步 capture 错误分类：
 * 可重试（建连失败、429、5xx）、不可重试（认证、校验、409）、结果不明（读超时）。
 */
public final class Mem0ErrorClassifier {

    public enum Kind {
        RETRYABLE,
        NON_RETRYABLE,
        UNKNOWN
    }

    private Mem0ErrorClassifier() {
    }

    public static Kind classify(Throwable ex) {
        if (ex == null) {
            return Kind.NON_RETRYABLE;
        }
        if (ex instanceof HttpClientErrorException clientError) {
            if (clientError.getStatusCode().value() == 429) {
                return Kind.RETRYABLE;
            }
            return Kind.NON_RETRYABLE;
        }
        if (ex instanceof HttpServerErrorException) {
            return Kind.RETRYABLE;
        }
        if (ex instanceof ResourceAccessException accessException) {
            Throwable cause = accessException.getCause();
            // 读超时属于结果不明，禁止盲目重试 add
            if (cause instanceof SocketTimeoutException) {
                return Kind.UNKNOWN;
            }
            return Kind.RETRYABLE;
        }
        return Kind.NON_RETRYABLE;
    }
}
