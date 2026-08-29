package com.h.otheragents.a2a.interfaces.web;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.otheragents.a2a.server.A2AAgentServer;
import org.a2aproject.sdk.spec.AgentCard;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * A2A HTTP 入口。请求/响应体显式走 Jackson 2（A2A SDK 与 JSON-RPC 处理栈的版本），
 * 不依赖 Spring Boot 4 的编解码器选择——Spring Framework 7 默认的 Jackson 3 编解码器
 * 无法反序列化 com.fasterxml 的 JsonNode，会让 message/send 直接 500。
 */
@RestController
public class A2AController {

    private final A2AAgentServer agentServer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public A2AController(A2AAgentServer agentServer) {
        this.agentServer = agentServer;
    }

    @GetMapping(value = "/a2a/agents/{agentId}/.well-known/agent-card.json",
            produces = MediaType.APPLICATION_JSON_VALUE)
    public String agentCard(@PathVariable String agentId) throws JsonProcessingException {
        AgentCard card = agentServer.card(agentId);
        return objectMapper.writeValueAsString(card);
    }

    @PostMapping(value = "/a2a/agents/{agentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public String jsonRpc(@PathVariable String agentId, @RequestBody String request)
            throws JsonProcessingException {
        JsonNode jsonRpcRequest = objectMapper.readTree(request);
        return objectMapper.writeValueAsString(agentServer.handle(agentId, jsonRpcRequest));
    }
}
