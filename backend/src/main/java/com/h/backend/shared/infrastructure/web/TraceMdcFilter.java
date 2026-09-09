package com.h.backend.shared.infrastructure.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * 请求级 traceId/spanId 注入 MDC，与 log4j2-spring.xml 日志格式中的
 * [SpanId:%X{spanId}] [TraceId:%X{traceId}] 对应。
 * 支持上游通过 X-Trace-Id / X-Span-Id 请求头透传（网关/前端拿同一 traceId 串联跨服务调用链），
 * 缺省时本地生成：traceId 32 位 hex、spanId 16 位 hex（对齐 OpenTelemetry ID 规格）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceMdcFilter extends OncePerRequestFilter {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String SPAN_ID_HEADER = "X-Span-Id";
    public static final String TRACE_ID_KEY = "traceId";
    public static final String SPAN_ID_KEY = "spanId";

    /** 仅允许安全字符，防止日志伪造（换行注入伪造日志行） */
    private static final Pattern SAFE_ID = Pattern.compile("^[A-Za-z0-9._-]{1,128}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String traceId = resolveId(request.getHeader(TRACE_ID_HEADER), 32);
        String spanId = resolveId(request.getHeader(SPAN_ID_HEADER), 16);
        MDC.put(TRACE_ID_KEY, traceId);
        MDC.put(SPAN_ID_KEY, spanId);
        response.setHeader(TRACE_ID_HEADER, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(TRACE_ID_KEY);
            MDC.remove(SPAN_ID_KEY);
        }
    }

    private String resolveId(String header, int hexLength) {
        if (header != null && SAFE_ID.matcher(header).matches()) {
            return header;
        }
        byte[] bytes = new byte[hexLength / 2];
        RANDOM.nextBytes(bytes);
        StringBuilder sb = new StringBuilder(hexLength);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
