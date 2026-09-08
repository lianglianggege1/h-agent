package com.h.backend.automation.infrastructure.scheduling;

import com.h.backend.automation.infrastructure.execution.AutomationProperties;
import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "automation.xxl-job", name = "enabled", havingValue = "true")
public class XxlJobExecutorConfig {

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor(AutomationProperties automationProperties) {
        AutomationProperties.XxlJob properties = automationProperties.getXxlJob();
        properties.requireSecureConfiguration();
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setEnabled(true);
        executor.setAdminAddresses(properties.getBaseUrl());
        executor.setAppname(properties.getExecutorAppName());
        executor.setAddress(properties.getExecutorAddress());
        executor.setIp(properties.getExecutorIp());
        executor.setPort(properties.getExecutorPort());
        executor.setAccessToken(properties.getAccessToken());
        executor.setTimeout((int) properties.getReadTimeout().toSeconds());
        executor.setLogPath(properties.getLogPath());
        executor.setLogRetentionDays(properties.getLogRetentionDays());
        return executor;
    }
}
