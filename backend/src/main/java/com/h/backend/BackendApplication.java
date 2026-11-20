package com.h.backend;

import com.h.capabilities.generation.infrastructure.config.GenerationProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan({
        "com.h.backend.user.infrastructure.persistence.mapper",
        "com.h.backend.chat.infrastructure.persistence.mapper",
        "com.h.capabilities.knowledge.infrastructure.persistence.mapper",
        "com.h.capabilities.generation.infrastructure.persistence.mapper",
        "com.h.backend.automation.infrastructure.persistence.mapper",
        "com.h.capabilities.skill.infrastructure.persistence.mapper",
        "com.h.capabilities.memory.infrastructure.persistence.mapper",
        "com.h.backend.document.infrastructure.persistence.mapper",
        "com.h.capabilities.document.infrastructure.persistence.mapper",
        "com.h.backend.order"
})
@SpringBootApplication(scanBasePackages = {"com.h.backend", "com.h.capabilities"})
@EnableScheduling
@EnableConfigurationProperties({GenerationProperties.class})
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}
