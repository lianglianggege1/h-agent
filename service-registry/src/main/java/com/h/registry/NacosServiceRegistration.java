package com.h.registry;

import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.web.server.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationListener;
import org.springframework.util.Assert;

import java.util.HashMap;

/** Registers the actual bound HTTP port, excluding a separate management server. */
final class NacosServiceRegistration implements ApplicationListener<WebServerInitializedEvent>, DisposableBean {
    private static final Logger log = LoggerFactory.getLogger(NacosServiceRegistration.class);
    private final NamingService namingService;
    private final NacosRegistryProperties properties;
    private final String serviceName;
    private final ApplicationContext context;
    private Instance instance;

    NacosServiceRegistration(NamingService namingService, NacosRegistryProperties properties,
            String serviceName, ApplicationContext context) {
        Assert.hasText(serviceName, "spring.application.name must not be blank");
        this.namingService = namingService;
        this.properties = properties;
        this.serviceName = serviceName;
        this.context = context;
    }

    @Override
    public synchronized void onApplicationEvent(WebServerInitializedEvent event) {
        if (event.getApplicationContext() != context || instance != null) {
            return;
        }
        Instance candidate = new Instance();
        var address = NacosRegistrationAddress.resolve(properties, event.getWebServer().getPort());
        candidate.setIp(address.ip());
        candidate.setPort(address.port());
        Assert.isTrue(candidate.getPort() > 0, "Nacos registration requires a bound HTTP port");
        candidate.setClusterName(properties.cluster());
        candidate.setEphemeral(true);
        candidate.setMetadata(properties.metadata() == null ? new HashMap<>() : new HashMap<>(properties.metadata()));
        // Retain even on failure, so context teardown can clean up a partially accepted registration.
        instance = candidate;
        try {
            namingService.registerInstance(serviceName, properties.group(), candidate);
        } catch (Exception e) {
            throw new IllegalStateException("Nacos registration failed for " + serviceName
                    + "; refusing to start", e);
        }
        log.info("Registered {} at {}:{} in Nacos namespace={} group={}", serviceName,
                candidate.getIp(), candidate.getPort(), properties.namespace(), properties.group());
    }

    @Override
    public synchronized void destroy() {
        if (instance == null) {
            return;
        }
        try {
            namingService.deregisterInstance(serviceName, properties.group(), instance);
        } catch (Exception e) {
            log.warn("Could not deregister {}; Nacos will expire the ephemeral instance", serviceName, e);
        } finally {
            instance = null;
        }
    }

}
