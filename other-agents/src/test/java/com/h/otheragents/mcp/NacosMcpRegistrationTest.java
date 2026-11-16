package com.h.otheragents.mcp;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.mcp.*;
import com.alibaba.nacos.api.exception.NacosException;
import com.h.registry.NacosRegistryProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.WebServer;
import org.springframework.boot.web.server.reactive.context.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NacosMcpRegistrationTest {
    private final AiService ai = mock(AiService.class);
    private final ReactiveWebServerApplicationContext context = mock(ReactiveWebServerApplicationContext.class);
    private final WebServer web = mock(WebServer.class);
    private final McpEndpointServers group = mock(McpEndpointServers.class);
    private final List<McpServerBasicInfo> published = new ArrayList<>();

    private NacosMcpRegistration registration() throws Exception {
        when(web.getPort()).thenReturn(18982);
        when(group.endpoints()).thenReturn(List.of(new McpEndpointServers.Endpoint("test1-mcp","1","/test1/mcp",List.of()),
                new McpEndpointServers.Endpoint("test2-mcp","1","/test2/mcp",List.of()),
                new McpEndpointServers.Endpoint("orders-mcp","1","/orders/mcp",List.of())));
        Map<String,McpServerDetailInfo> definitions = new HashMap<>();
        when(ai.releaseMcpServer(any(),any())).thenAnswer(call -> {
            McpServerBasicInfo source = call.getArgument(0); published.add(source);
            var info = new McpServerDetailInfo(); info.setProtocol(source.getProtocol());
            info.setRemoteServerConfig(source.getRemoteServerConfig()); info.setToolSpec(call.getArgument(1));
            definitions.put(source.getName(),info); return "id";
        });
        when(ai.getMcpServer(anyString(),eq("1"))).thenAnswer(call -> definitions.get(call.getArgument(0)));
        return new NacosMcpRegistration(ai,new NacosRegistryProperties("localhost:8848","public","DEFAULT_GROUP","DEFAULT",
                "","","127.0.0.1",0,5000,Map.of()),List.of(group),context);
    }
    @Test
    void publishesThreeIndependentDefinitionsAndOnlyCleansRuntimeEndpoints() throws Exception {
        var registration = registration(); var event = new ReactiveWebServerInitializedEvent(web,context);
        registration.onApplicationEvent(new ReactiveWebServerInitializedEvent(web,mock(ReactiveWebServerApplicationContext.class)));
        verifyNoInteractions(ai);
        registration.onApplicationEvent(event); registration.onApplicationEvent(event);
        assertEquals(List.of("/test1/mcp","/test2/mcp","/orders/mcp"), published.stream().map(d -> d.getRemoteServerConfig().getExportPath()).toList());
        for (String name : List.of("test1-mcp","test2-mcp","orders-mcp")) verify(ai).registerMcpServerEndpoint(name,"127.0.0.1",18982,"1");
        registration.destroy(); registration.destroy();
        for (String name : List.of("test1-mcp","test2-mcp","orders-mcp")) verify(ai).deregisterMcpServerEndpoint(name,"127.0.0.1",18982);
    }
    @Test
    void cleansAcceptedAndUncertainRegistrationsOnPartialFailure() throws Exception {
        var registration = registration();
        doThrow(new NacosException(500,"response lost")).when(ai).registerMcpServerEndpoint("test2-mcp","127.0.0.1",18982,"1");
        assertThrows(IllegalStateException.class, () -> registration.onApplicationEvent(new ReactiveWebServerInitializedEvent(web,context)));
        verify(ai).deregisterMcpServerEndpoint("test1-mcp","127.0.0.1",18982);
        verify(ai).deregisterMcpServerEndpoint("test2-mcp","127.0.0.1",18982);
        verify(ai,never()).registerMcpServerEndpoint(eq("orders-mcp"),anyString(),anyInt(),anyString());
    }
    @Test
    void acceptsExistingCompatibleVersionAfter409OnRestart() throws Exception {
        var registration = registration(); var event = new ReactiveWebServerInitializedEvent(web,context);
        registration.onApplicationEvent(event); registration.destroy();
        doThrow(new NacosException(409,"already exists")).when(ai).releaseMcpServer(any(),any());
        registration.onApplicationEvent(event);
        verify(ai,times(2)).registerMcpServerEndpoint("test1-mcp","127.0.0.1",18982,"1");
        registration.destroy();
    }

    @Test
    void incompatibleExistingDefinitionRefusesRegistration() throws Exception {
        var registration = registration();
        var incompatible = new McpServerDetailInfo(); incompatible.setProtocol("sse");
        when(ai.getMcpServer(anyString(),eq("1"))).thenReturn(incompatible);
        assertThrows(IllegalStateException.class, () -> registration.onApplicationEvent(new ReactiveWebServerInitializedEvent(web,context)));
        verify(ai,never()).registerMcpServerEndpoint(anyString(),anyString(),anyInt(),anyString());
    }
}
