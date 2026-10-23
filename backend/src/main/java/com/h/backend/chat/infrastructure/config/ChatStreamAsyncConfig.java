package com.h.backend.chat.infrastructure.config;

import com.h.backend.shared.infrastructure.web.ExecutionLogContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Configuration
public class ChatStreamAsyncConfig {

    @Bean(destroyMethod = "close")
    public ExecutorService chatStreamExecutor() {
        var threads = Thread.ofVirtual().name("chat-stream-", 0).factory();
        return Executors.newThreadPerTaskExecutor(task ->
                threads.newThread(ExecutionLogContext.wrap(task)));
    }
}
