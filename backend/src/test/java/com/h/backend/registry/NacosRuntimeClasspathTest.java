package com.h.backend.registry;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NacosRuntimeClasspathTest {
    @Test
    void rpcImplementationComesOnlyFromShadedClient() throws Exception {
        for (String resource : new String[]{"com/alibaba/nacos/common/remote/client/RpcClientFactory.class",
                "com/alibaba/nacos/api/grpc/auto/RequestGrpc.class"}) {
            var implementations = Collections.list(getClass().getClassLoader().getResources(resource));
            assertEquals(1, implementations.size(),
                    "Unshaded Nacos classes must not shadow the runtime client's shaded gRPC implementation: " + implementations);
        }
    }
}
