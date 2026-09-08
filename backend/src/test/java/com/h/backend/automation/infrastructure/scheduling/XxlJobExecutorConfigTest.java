package com.h.backend.automation.infrastructure.scheduling;

import com.h.backend.automation.infrastructure.execution.AutomationProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class XxlJobExecutorConfigTest {

    @Test
    void enabledIntegrationRequiresAdminPassword() {
        AutomationProperties properties = new AutomationProperties();
        properties.getXxlJob().setEnabled(true);
        properties.getXxlJob().setPassword("");
        properties.getXxlJob().setAccessToken("");

        assertThrows(IllegalStateException.class,
                () -> new XxlJobExecutorConfig().xxlJobExecutor(properties));
    }
}
