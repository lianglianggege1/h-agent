package com.h.registry;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class NacosRegistryLifecycleTest {
    private static NamingService namingService;

    @BeforeEach
    void setUp() {
        namingService = mock(NamingService.class);
    }

    @Test
    void registersBoundPortAndMetadataThenDeregistersAndClosesClient() throws Exception {
        Instance registered;
        try (var context = start("h-agent.registry.metadata.protocol=http", "h-agent.registry.cluster=lan")) {
            var captor = ArgumentCaptor.forClass(Instance.class);
            verify(namingService).registerInstance(eq("registry-test"), eq("test-group"), captor.capture());
            registered = captor.getValue();
            assertThat(registered.getPort()).isEqualTo(((WebServerApplicationContext) context).getWebServer().getPort());
            assertThat(registered.getIp()).isEqualTo("192.0.2.10");
            assertThat(registered.getClusterName()).isEqualTo("lan");
            assertThat(registered.isEphemeral()).isTrue();
            assertThat(registered.getMetadata()).containsEntry("protocol", "http");
            assertThat(context.getBean(NacosServiceDiscovery.class)).isNotNull();
        }
        verify(namingService).deregisterInstance("registry-test", "test-group", registered);
        verify(namingService).shutDown();
    }

    @Test
    void reactiveServiceRegistersItsActualPort() throws Exception {
        try (var context = start("spring.main.web-application-type=reactive")) {
            assertThat(context).isInstanceOf(org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext.class);
            var captor = ArgumentCaptor.forClass(Instance.class);
            verify(namingService).registerInstance(eq("registry-test"), eq("test-group"), captor.capture());
            assertThat(captor.getValue().getPort()).isEqualTo(((WebServerApplicationContext) context).getWebServer().getPort());
        }
    }

    @Test
    void registrationFailureAbortsRealWebApplicationStartupAndCleansUp() throws Exception {
        doThrow(new NacosException(500, "registry unavailable"))
                .when(namingService).registerInstance(eq("registry-test"), eq("test-group"), any(Instance.class));

        assertThatThrownBy(() -> start())
                .hasStackTraceContaining("Nacos registration failed for registry-test; refusing to start");

        verify(namingService).deregisterInstance(eq("registry-test"), eq("test-group"), any(Instance.class));
        verify(namingService).shutDown();
    }

    @Test
    void managementPortDoesNotCreateSecondRegistration() throws Exception {
        try (var context = start("h-agent.registry.port=18083")) {
            var child = mock(org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext.class);
            var server = mock(org.springframework.boot.web.server.WebServer.class);
            context.getBean(NacosServiceRegistration.class).onApplicationEvent(
                    new org.springframework.boot.web.server.servlet.context.ServletWebServerInitializedEvent(server, child));
            var captor = ArgumentCaptor.forClass(Instance.class);
            verify(namingService).registerInstance(eq("registry-test"), eq("test-group"), captor.capture());
            assertThat(captor.getValue().getPort()).isEqualTo(18083);
        }
    }

    @Test
    void testOptOutDoesNotRegisterOrCreateDiscovery() throws Exception {
        try (var context = start("h-agent.registry.enabled=false")) {
            assertThat(context.getBeansOfType(NacosServiceDiscovery.class)).isEmpty();
            verify(namingService, never()).registerInstance(any(), any(), any(Instance.class));
            verifyNoInteractions(namingService);
        }
    }

    @Test
    void invalidPortIsRejectedBeforeRegistration() throws Exception {
        assertThatThrownBy(() -> start("h-agent.registry.port=65536"))
                .hasStackTraceContaining("h-agent.registry");
        verify(namingService, never()).registerInstance(any(), any(), any(Instance.class));
    }

    private ConfigurableApplicationContext start(String... overrides) {
        return new SpringApplicationBuilder(TestApplication.class)
                .logStartupInfo(false)
                .properties("server.port=0", "spring.application.name=registry-test",
                        "h-agent.registry.ip=192.0.2.10", "h-agent.registry.group=test-group",
                        "spring.main.banner-mode=off", "logging.level.root=OFF")
                .run(java.util.Arrays.stream(overrides).map(value -> "--" + value).toArray(String[]::new));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class TestApplication {
        @Bean(destroyMethod = "shutDown")
        NamingService testNamingService() {
            return namingService;
        }
    }
}
