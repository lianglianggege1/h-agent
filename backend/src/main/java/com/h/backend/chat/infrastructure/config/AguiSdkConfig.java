package com.h.backend.chat.infrastructure.config;

import com.agui.community.core.serialization.Serializer;
import com.agui.community.spring.server.core.JacksonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AguiSdkConfig {
    @Bean
    public Serializer aguiSerializer() {
        // The SDK owns Jackson 2; discover its Java-time module for product DTO timestamps.
        return new JacksonSerializer(new ObjectMapper().findAndRegisterModules()
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
    }
}
