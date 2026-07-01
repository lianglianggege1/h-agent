package com.h.backend.chat.infrastructure.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ChatStreamAsyncConfig {

    @Bean(destroyMethod = "close")
    public ExecutorService chatStreamExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
