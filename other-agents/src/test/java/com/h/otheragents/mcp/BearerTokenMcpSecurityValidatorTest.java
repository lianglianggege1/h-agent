package com.h.otheragents.mcp;

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BearerTokenMcpSecurityValidatorTest {

    private final BearerTokenMcpSecurityValidator validator = new BearerTokenMcpSecurityValidator("secret-token");

    @Test
    void acceptsValidBearerToken() {
        assertThatCode(() -> validator.validateHeaders(
                Map.of("Authorization", List.of("Bearer secret-token"))))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingAuthorizationHeader() {
        assertThatThrownBy(() -> validator.validateHeaders(Map.of()))
                .isInstanceOfSatisfying(ServerTransportSecurityException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(401));
    }

    @Test
    void rejectsNonBearerScheme() {
        assertThatThrownBy(() -> validator.validateHeaders(
                Map.of("Authorization", List.of("Basic secret-token"))))
                .isInstanceOfSatisfying(ServerTransportSecurityException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(401));
    }

    @Test
    void rejectsWrongToken() {
        assertThatThrownBy(() -> validator.validateHeaders(
                Map.of("Authorization", List.of("Bearer wrong-token"))))
                .isInstanceOfSatisfying(ServerTransportSecurityException.class,
                        e -> assertThat(e.getStatusCode()).isEqualTo(401));
    }

    @Test
    void headerNameIsCaseInsensitive() {
        assertThatCode(() -> validator.validateHeaders(
                Map.of("authorization", List.of("Bearer secret-token"))))
                .doesNotThrowAnyException();
    }

    @Test
    void blankTokenIsRejectedAtConstruction() {
        assertThatThrownBy(() -> new BearerTokenMcpSecurityValidator(" "))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
