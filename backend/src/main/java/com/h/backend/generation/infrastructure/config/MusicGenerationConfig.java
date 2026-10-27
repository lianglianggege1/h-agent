package com.h.backend.generation.infrastructure.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
@EnableConfigurationProperties(MusicGenerationProperties.class)
public class MusicGenerationConfig {
    @Bean(destroyMethod = "close")
    public ExecutorService musicGenerationExecutor() {
        return Executors.newFixedThreadPool(2, Thread.ofVirtual().name("music-generation-", 0).factory());
    }
}
