package com.h.otheragents.a2a.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.h.otheragents.a2a.server.A2AAgentServer;
import org.a2aproject.sdk.spec.AgentCard;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class A2AController {

    private final A2AAgentServer agentServer;

    public A2AController(A2AAgentServer agentServer) {
        this.agentServer = agentServer;
    }

    @GetMapping("/a2a/agents/{agentId}/.well-known/agent-card.json")
    public AgentCard agentCard(@PathVariable String agentId) {
        return agentServer.card(agentId);
    }

    @PostMapping("/a2a/agents/{agentId}")
    public JsonNode jsonRpc(@PathVariable String agentId, @RequestBody JsonNode request) {
        return agentServer.handle(agentId, request);
    }
}
