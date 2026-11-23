package com.h.backend.common.api;

import com.h.capabilities.shared.infrastructure.web.ExecutionLogContext;
import io.opentelemetry.api.trace.Span;
import org.slf4j.MDC;

public record ApiResponse<T>(
        int code,
        String message,
        T data,
        String traceId
) {
    public ApiResponse(int code, String message, T data) {
        this(code, message, data, currentTraceId());
    }

    private static String currentTraceId() {
        var span = Span.current().getSpanContext();
        return span.isValid() ? span.getTraceId() : MDC.get(ExecutionLogContext.TRACE_ID_KEY);
    }

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "OK", data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
