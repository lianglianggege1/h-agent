package com.h.backend.evaluation.infrastructure.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class EvaluationIsolationGuard {

    private final EvaluationProperties properties;
    private final String jdbcUrl;
    private final int redisDatabase;

    public EvaluationIsolationGuard(
            EvaluationProperties properties,
            @Value("${spring.datasource.url:}") String jdbcUrl,
            @Value("${spring.data.redis.database:0}") int redisDatabase
    ) {
        this.properties = properties;
        this.jdbcUrl = jdbcUrl;
        this.redisDatabase = redisDatabase;
    }

    @PostConstruct
    void validateAtStartup() {
        validate(properties, jdbcUrl, redisDatabase);
    }

    static void validate(EvaluationProperties properties, String jdbcUrl, int redisDatabase) {
        if (!properties.isEnabled()) {
            return;
        }
        if (jdbcUrl == null || !jdbcUrl.contains(properties.getRequiredJdbcUrlMarker())) {
            throw new IllegalStateException("evaluation database URL does not contain the required isolation marker");
        }
        if (redisDatabase != properties.getRequiredRedisDatabase()) {
            throw new IllegalStateException("evaluation Redis database does not match the isolated database");
        }
    }
}
