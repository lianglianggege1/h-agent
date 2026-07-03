package com.h.otheragents.a2a.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JsonRpcA2ATransportWrapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void preservesRpcIdAndWrapsHandlerResult() throws Exception {
        A2ARequestHandler handler = (agentId, method, params) -> {
            return objectMapper.readTree("""
                    {
                      "kind": "task",
                      "id": "task-1",
                      "contextId": "context-1",
                      "status": {"state": "completed"}
                    }
                    """);
        };
        JsonRpcA2ATransportWrapper wrapper = new JsonRpcA2ATransportWrapper(handler);

        JsonNode response = wrapper.handle("creative-writer", objectMapper.readTree("""
                {
                  "jsonrpc": "2.0",
                  "id": "rpc-1",
                  "method": "message/send",
                  "params": {"message": {"parts": []}}
                }
                """));

        assertEquals("2.0", response.path("jsonrpc").asText());
        assertEquals("rpc-1", response.path("id").asText());
        assertEquals("task-1", response.path("result").path("id").asText());
    }

    @Test
    void unsupportedMethodReturnsMethodNotFound() throws Exception {
        JsonRpcA2ATransportWrapper wrapper = new JsonRpcA2ATransportWrapper((agentId, method, params) -> objectMapper.nullNode());

        JsonNode response = wrapper.handle("creative-writer", objectMapper.readTree("""
                {
                  "jsonrpc": "2.0",
                  "id": 7,
                  "method": "tasks/cancel",
                  "params": {}
                }
                """));

        assertEquals(7, response.path("id").asInt());
        assertEquals(-32601, response.path("error").path("code").asInt());
    }
}
