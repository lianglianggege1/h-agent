package com.h.registry;

import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.constant.AiConstants;
import com.alibaba.nacos.api.ai.model.mcp.*;
import com.alibaba.nacos.api.ai.model.mcp.registry.ServerVersionDetail;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NacosMcpDiscoveryTest {
    private McpServerDetailInfo definition() {
        var info = new McpServerDetailInfo(); info.setProtocol(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE);
        var version = new ServerVersionDetail(); version.setVersion("1"); info.setVersionDetail(version);
        return info;
    }
    private McpEndpointInfo endpoint(int port, String path) {
        var endpoint = new McpEndpointInfo(); endpoint.setProtocol(null); endpoint.setAddress("127.0.0.1");
        endpoint.setPort(port); endpoint.setPath(path); return endpoint;
    }
    @Test
    void pinsVersionAndKeepsCurrentInstanceUntilItDisappears() throws Exception {
        var ai = mock(AiService.class); var info = definition();
        info.setBackendEndpoints(List.of(endpoint(8082,"/test1/mcp"),endpoint(8084,"/test1/mcp")));
        when(ai.getMcpServer("test1-mcp","1")).thenReturn(info);
        var discovery = new NacosMcpDiscovery(ai);
        assertEquals("http://127.0.0.1:8084/test1/mcp", discovery.resolve("test1-mcp","1","http://127.0.0.1:8084/test1/mcp"));
        info.setBackendEndpoints(List.of(endpoint(8082,"/test1/mcp")));
        assertEquals("http://127.0.0.1:8082/test1/mcp", discovery.resolve("test1-mcp","1","http://127.0.0.1:8084/test1/mcp"));
        info.setBackendEndpoints(List.of());
        assertThrows(IllegalStateException.class, () -> discovery.resolve("test1-mcp","1",null));
    }
    @Test
    void rejectsDisabledWrongProtocolWrongVersionAndMalformedAddresses() throws Exception {
        var ai = mock(AiService.class); var info = definition(); var discovery = new NacosMcpDiscovery(ai);
        when(ai.getMcpServer("test1-mcp","1")).thenReturn(info);
        info.setEnabled(false);
        assertThrows(IllegalStateException.class, () -> discovery.resolve("test1-mcp","1",null));
        info.setEnabled(true); info.setProtocol("sse");
        assertThrows(IllegalStateException.class, () -> discovery.resolve("test1-mcp","1",null));
        info.setProtocol(AiConstants.Mcp.MCP_PROTOCOL_STREAMABLE); info.getVersionDetail().setVersion("2");
        assertThrows(IllegalStateException.class, () -> discovery.resolve("test1-mcp","1",null));
        info.getVersionDetail().setVersion("1"); info.setBackendEndpoints(List.of(endpoint(0,"/mcp")));
        assertThrows(IllegalArgumentException.class, () -> discovery.resolve("test1-mcp","1",null));
    }
}
