package com.h.otheragents.a2a.interfaces.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.h.otheragents.a2a.application.A2AAgentCardApplicationService;
import com.h.otheragents.a2a.infrastructure.ai.Agents;
import io.a2a.spec.AgentCard;
import io.a2a.spec.InvalidRequestError;
import io.a2a.spec.Message;
import io.a2a.spec.MethodNotFoundError;
import io.a2a.spec.SendMessageRequest;
import io.a2a.spec.SendMessageResponse;
import io.a2a.spec.TextPart;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
public class A2AController {

    private final A2AAgentCardApplicationService agentCardApplicationService;
    private final Agents.CreativeWriter creativeWriter;
    private final Agents.AudienceEditor audienceEditor;
    private final Agents.StyleEditor styleEditor;

    public A2AController(
            A2AAgentCardApplicationService agentCardApplicationService,
            Agents.CreativeWriter creativeWriter,
            Agents.AudienceEditor audienceEditor,
            Agents.StyleEditor styleEditor
    ) {
        this.agentCardApplicationService = agentCardApplicationService;
        this.creativeWriter = creativeWriter;
        this.audienceEditor = audienceEditor;
        this.styleEditor = styleEditor;
    }

    @GetMapping("/creative-writer/.well-known/agent-card.json")
    public AgentCard creativeWriterAgentCard() {
        return agentCardApplicationService.creativeWriterAgentCard();
    }

    @GetMapping("/audience-editor/.well-known/agent-card.json")
    public AgentCard audienceEditorAgentCard() {
        return agentCardApplicationService.audienceEditorAgentCard();
    }

    @GetMapping("/style-editor/.well-known/agent-card.json")
    public AgentCard styleEditorAgentCard() {
        return agentCardApplicationService.styleEditorAgentCard();
    }

    @PostMapping("/creative-writer/a2a")
    public SendMessageResponse creativeWriterJsonRpc(@RequestBody JsonNode request) {

        return handle(request, 1, prompts -> creativeWriter.generateStory(prompts.getFirst()), "creative-writer");
    }

    @PostMapping("/audience-editor/a2a")
    public SendMessageResponse audienceEditorJsonRpc(@RequestBody JsonNode request) {
        return handle(request, 2, prompts -> audienceEditor.editStory(prompts.get(0), prompts.get(1)), "audience-editor");
    }

    @PostMapping("/style-editor/a2a")
    public SendMessageResponse styleEditorJsonRpc(@RequestBody JsonNode request) {
        return handle(request, 2, prompts -> styleEditor.editStory(prompts.get(0), prompts.get(1)), "style-editor");
    }

    private SendMessageResponse handle(JsonNode request, int requiredParts, AgentCall agentCall, String agentName) {
        Object id = jsonRpcId(request.path("id"));
        String method = request.path("method").asText("");
        if (!SendMessageRequest.METHOD.equals(method)) {
            return new SendMessageResponse(id, new MethodNotFoundError());
        }

        List<String> prompts = textParts(request.path("params").path("message").path("parts"));
        if (prompts.size() < requiredParts) {
            return new SendMessageResponse(id, new InvalidRequestError("message parts must contain required text"));
        }

        Message response = new Message.Builder()
                .role(Message.Role.AGENT)
                .parts(new TextPart(agentCall.call(prompts)))
                .metadata(Map.of("provider", "other-agents", "agent", agentName))
                .build();
        return new SendMessageResponse(id, response);
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

    @FunctionalInterface
    private interface AgentCall {
        String call(List<String> prompts);
    }
}
