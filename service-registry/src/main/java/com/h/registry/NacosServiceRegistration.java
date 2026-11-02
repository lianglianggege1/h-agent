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

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
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
        candidate.setIp(properties.ip().isBlank() ? localIp() : properties.ip());
        candidate.setPort(properties.port() == 0 ? event.getWebServer().getPort() : properties.port());
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

    private static String localIp() {
        try {
            String fallback = null;
            for (NetworkInterface network : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!network.isUp() || network.isLoopback() || network.isVirtual()) {
                    continue;
                }
                for (var address : Collections.list(network.getInetAddresses())) {
                    if (address instanceof Inet4Address && !address.isLoopbackAddress()) {
                        if (address.isSiteLocalAddress()) {
                            return address.getHostAddress();
                        }
                        fallback = address.getHostAddress();
                    }
                }
            }
            if (fallback != null) {
                return fallback;
            }
        } catch (SocketException e) {
            throw new IllegalStateException("Cannot determine registration IP; set NACOS_IP", e);
        }
        throw new IllegalStateException("No non-loopback IPv4 address; set NACOS_IP");
    }
}
