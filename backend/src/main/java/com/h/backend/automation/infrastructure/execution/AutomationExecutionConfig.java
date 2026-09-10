package com.h.backend.automation.infrastructure.execution;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(AutomationProperties.class)
public class AutomationExecutionConfig {
}
