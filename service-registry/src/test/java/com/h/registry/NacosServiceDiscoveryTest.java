package com.h.registry;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NacosServiceDiscoveryTest {
    private final NamingService namingService = mock(NamingService.class);
    private final NacosServiceDiscovery discovery = new NacosServiceDiscovery(namingService, "h-agent");

    @Test
    void queriesHealthySubscribedInstancesInConfiguredGroupAndExcludesDisabledOrZeroWeight() throws Exception {
        var enabled = instance("192.0.2.1");
        var disabled = instance("192.0.2.2");
        disabled.setEnabled(false);
        var zeroWeight = instance("192.0.2.3");
        zeroWeight.setWeight(0);
        when(namingService.selectInstances("other-agents", "h-agent", true, true))
                .thenReturn(List.of(enabled, disabled, zeroWeight));

        assertThat(discovery.healthyInstances("other-agents"))
                .containsExactly(new NacosServiceDiscovery.ServiceInstance("192.0.2.1", 8082, Map.of("protocol", "http")));
    }

    @Test
    void noInstancesReturnsEmptyAndClientErrorsPropagateWithoutFixedAddressFallback() throws Exception {
        when(namingService.selectInstances("other-agents", "h-agent", true, true)).thenReturn(List.of());
        assertThat(discovery.healthyInstances("other-agents")).isEmpty();
        when(namingService.selectInstances("other-agents", "h-agent", true, true))
                .thenThrow(new NacosException(500, "no cache available"));
        assertThatThrownBy(() -> discovery.healthyInstances("other-agents")).isInstanceOf(NacosException.class);
    }

    private Instance instance(String ip) {
        var instance = new Instance();
        instance.setIp(ip);
        instance.setPort(8082);
        instance.setMetadata(Map.of("protocol", "http"));
        return instance;
    }
}
