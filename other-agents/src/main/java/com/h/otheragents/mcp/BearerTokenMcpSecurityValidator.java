package com.h.otheragents.mcp;

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Map;

/**
 * 按 endpoint 校验 Authorization: Bearer Token 的 MCP transport 安全校验器。
 * 校验失败抛 401，由 transport 直接转成 HTTP 401 响应。
 */
public class BearerTokenMcpSecurityValidator implements ServerTransportSecurityValidator {

    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";

    private final byte[] expectedToken;

    public BearerTokenMcpSecurityValidator(String token) {
        if (token == null || token.isBlank()) {
            throw new IllegalArgumentException("MCP endpoint token must not be blank");
        }
        this.expectedToken = token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public void validateHeaders(Map<String, List<String>> headers) throws ServerTransportSecurityException {
        String authorization = findAuthorization(headers);
        if (authorization == null || authorization.isBlank()) {
            throw new ServerTransportSecurityException(401, "Missing Authorization header");
        }
        if (!authorization.startsWith(BEARER_PREFIX)) {
            throw new ServerTransportSecurityException(401, "Unsupported authorization scheme");
        }
        String presented = authorization.substring(BEARER_PREFIX.length()).trim();
        // 常量时间比较，避免时序侧信道泄露 token
        if (!MessageDigest.isEqual(expectedToken, presented.getBytes(StandardCharsets.UTF_8))) {
            throw new ServerTransportSecurityException(401, "Invalid MCP endpoint token");
        }
    }

    private String findAuthorization(Map<String, List<String>> headers) {
        for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
            if (AUTHORIZATION_HEADER.equalsIgnoreCase(entry.getKey())) {
                List<String> values = entry.getValue();
                return (values == null || values.isEmpty()) ? null : values.get(0);
            }
        }
        return null;
    }
}
