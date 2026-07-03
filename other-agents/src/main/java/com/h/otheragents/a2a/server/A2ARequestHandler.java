package com.h.otheragents.a2a.server;

import com.fasterxml.jackson.databind.JsonNode;

@FunctionalInterface
public interface A2ARequestHandler {

    JsonNode handle(String agentId, String method, JsonNode params) throws Exception;

    static A2ARequestHandler messageSend(A2AAgentExecutor executor) {
        return (agentId, method, params) -> executor.execute(agentId, params.path("message"));
    }
}
