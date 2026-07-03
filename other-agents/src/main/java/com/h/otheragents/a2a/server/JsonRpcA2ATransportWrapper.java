package com.h.otheragents.a2a.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

public class JsonRpcA2ATransportWrapper {

    public static final String MESSAGE_SEND = "message/send";

    private final A2ARequestHandler requestHandler;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JsonRpcA2ATransportWrapper(A2ARequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    public JsonNode handle(String agentId, JsonNode request) {
        JsonNode id = request.path("id");
        String method = request.path("method").asText("");
        if (!MESSAGE_SEND.equals(method)) {
            return error(id, -32601, "Method not found");
        }
        JsonNode params = request.path("params");
        if (!params.has("message")) {
            return error(id, -32600, "Invalid request");
        }
        try {
            return success(id, requestHandler.handle(agentId, method, params));
        } catch (IllegalArgumentException error) {
            return error(id, -32600, error.getMessage());
        } catch (Exception error) {
            return error(id, -32603, error.getMessage());
        }
    }

    private ObjectNode success(JsonNode id, JsonNode result) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id.isMissingNode() ? objectMapper.nullNode() : id);
        response.set("result", result);
        return response;
    }

    private ObjectNode error(JsonNode id, int code, String message) {
        ObjectNode response = objectMapper.createObjectNode();
        response.put("jsonrpc", "2.0");
        response.set("id", id.isMissingNode() ? objectMapper.nullNode() : id);
        ObjectNode error = response.putObject("error");
        error.put("code", code);
        error.put("message", message);
        return response;
    }
}
