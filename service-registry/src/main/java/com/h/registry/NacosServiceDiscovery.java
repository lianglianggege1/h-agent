package com.h.registry;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.springframework.util.Assert;

import java.util.List;
import java.util.Map;

/** Shared discovery entry point; routing and protocol integration belong to callers. */
public final class NacosServiceDiscovery {
    private final NamingService namingService;
    private final String group;

    public NacosServiceDiscovery(NamingService namingService, String group) {
        this.namingService = namingService;
        this.group = group;
    }

    public List<ServiceInstance> healthyInstances(String serviceName) throws NacosException {
        Assert.hasText(serviceName, "serviceName must not be blank");
        // subscribe=true lets the SDK maintain its push-based local discovery cache and reconnect.
        return namingService.selectInstances(serviceName, group, true, true).stream()
                .filter(Instance::isEnabled)
                .filter(instance -> instance.getWeight() > 0)
                .map(instance -> new ServiceInstance(instance.getIp(), instance.getPort(),
                        Map.copyOf(instance.getMetadata())))
                .toList();
    }

    public record ServiceInstance(String ip, int port, Map<String, String> metadata) { }
}
