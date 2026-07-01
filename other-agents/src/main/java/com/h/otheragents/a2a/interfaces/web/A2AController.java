package com.h.otheragents.a2a.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.h.otheragents.a2a.application.A2AAgentCardApplicationService;
import com.h.otheragents.a2a.application.A2AMessageApplicationService;
import io.a2a.spec.AgentCard;
import io.a2a.spec.InvalidRequestError;
import io.a2a.spec.MethodNotFoundError;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendMessageResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
public class A2AController {

    private final A2AAgentCardApplicationService agentCardApplicationService;
    private final A2AMessageApplicationService messageApplicationService;

    public A2AController(
            A2AAgentCardApplicationService agentCardApplicationService,
            A2AMessageApplicationService messageApplicationService
    ) {
        this.agentCardApplicationService = agentCardApplicationService;
        this.messageApplicationService = messageApplicationService;
    }

    @GetMapping("/.well-known/agent-card.json")
    public AgentCard agentCard() {
        return agentCardApplicationService.agentCard();
    }

    @PostMapping({"/a2a", "/", ""})
    public SendMessageResponse jsonRpc(@RequestBody JsonNode request) {
        Object id = jsonRpcId(request.path("id"));
        String method = request.path("method").asText("");
        if (!SendMessageRequest.METHOD.equals(method)) {
            return new SendMessageResponse(id, new MethodNotFoundError());
        }

        List<String> prompts = textParts(request.path("params").path("message").path("parts"));
        if (prompts.isEmpty()) {
            return new SendMessageResponse(id, new InvalidRequestError("message parts must contain text"));
        }

        return new SendMessageResponse(id, messageApplicationService.handleMessage(prompts, agentType(request)));
    }

    private static Object jsonRpcId(JsonNode id) {
        if (id == null || id.isMissingNode() || id.isNull()) {
            return null;
        }
        if (id.isIntegralNumber()) {
            return id.asInt();
        }
        return id.asText();
    }

    private static List<String> textParts(JsonNode parts) {
        List<String> texts = new ArrayList<>();
        if (parts == null || !parts.isArray()) {
            return texts;
        }
        for (JsonNode part : parts) {
            String text = part.path("text").asText("");
            if (!text.isBlank()) {
                texts.add(text);
            }
        }
        return texts;
    }

    private static String agentType(JsonNode request) {
        JsonNode params = request.path("params");
        String agent = params.path("metadata").path("agent").asText("");
        if (!agent.isBlank()) {
            return agent;
        }
        return params.path("message").path("metadata").path("agent").asText("");
    }
}
