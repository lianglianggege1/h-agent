package com.h.backend.shared.infrastructure.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.task.VirtualThreadTaskExecutor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class WebMvcAsyncConfigTest {

    @Test
    void shouldUseVirtualThreadsAndDisableTheMvcAsyncTimeout() {
        AsyncSupportConfigurer configurer = mock(AsyncSupportConfigurer.class);

        new WebMvcAsyncConfig().configureAsyncSupport(configurer);

        verify(configurer).setTaskExecutor(any(VirtualThreadTaskExecutor.class));
        verify(configurer).setDefaultTimeout(-1);
    }
}
