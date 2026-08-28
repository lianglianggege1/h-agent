package com.h.backend;

import com.h.backend.voice.infrastructure.config.VoiceTtsProperties;
import com.h.backend.generation.infrastructure.config.GenerationProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;

@MapperScan({
        "com.h.backend.user.infrastructure.persistence.mapper",
        "com.h.backend.chat.infrastructure.persistence.mapper",
        "com.h.backend.knowledge.infrastructure.persistence.mapper",
        "com.h.backend.generation.infrastructure.persistence.mapper",
        "com.h.backend.skill.infrastructure.persistence.mapper"
})
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({VoiceTtsProperties.class, GenerationProperties.class})
public class BackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(BackendApplication.class, args);
    }

}
