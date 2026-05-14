package com.h.backend;

import com.h.backend.security.JwtTokenProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
class BackendApplicationTests {

    @Autowired(required = false)
    private JwtTokenProvider jwtTokenProvider;

    @Test
    void shouldLoadJwtBean() {
        assertNotNull(jwtTokenProvider);
    }

}
