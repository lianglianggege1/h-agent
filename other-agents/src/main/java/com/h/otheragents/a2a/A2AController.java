package com.h.otheragents.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import io.a2a.spec.AgentCapabilities;
import io.a2a.spec.AgentCard;
import io.a2a.spec.AgentProvider;
import io.a2a.spec.AgentSkill;
import io.a2a.spec.InvalidRequestError;
import io.a2a.spec.Message;
import io.a2a.spec.MethodNotFoundError;
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

    private static final String METHOD_MESSAGE_SEND = "message/send";

    private final OtherAgentsA2AProperties properties;
    private final CreativeWriterService creativeWriterService;

    public A2AController(OtherAgentsA2AProperties properties, CreativeWriterService creativeWriterService) {
        this.properties = properties;
        this.creativeWriterService = creativeWriterService;
    }

    @GetMapping("/.well-known/agent-card.json")
    public AgentCard agentCard() {
        String baseUrl = normalizedBaseUrl();
        String jsonRpcUrl = baseUrl + "/a2a";
        return new AgentCard.Builder()
                .name("remote-creative-writer")
                .description("通过 A2A 暴露的远端创意写作者")
                .url(jsonRpcUrl)
                .provider(new AgentProvider("h-agent other-agents", baseUrl))
                .version("0.1.0")
                .capabilities(new AgentCapabilities.Builder()
                        .streaming(false)
                        .pushNotifications(false)
                        .stateTransitionHistory(false)
                        .build())
                .defaultInputModes(List.of("text/plain"))
                .defaultOutputModes(List.of("text/plain"))
                .skills(List.of(new AgentSkill.Builder()
                        .id("creative-writing")
                        .name("创意故事初稿")
                        .description("根据主题生成故事初稿")
                        .tags(List.of("story", "writing", "demo"))
                        .examples(List.of("月球救援", "赛博朋克城市"))
                        .inputModes(List.of("text/plain"))
                        .outputModes(List.of("text/plain"))
                        .build()))
                .build();
    }

    @PostMapping({"/a2a", "/", ""})
    public SendMessageResponse jsonRpc(@RequestBody JsonNode request) {
        Object id = jsonRpcId(request.path("id"));
        String method = request.path("method").asText("");
        if (!METHOD_MESSAGE_SEND.equals(method)) {
            return new SendMessageResponse(id, new MethodNotFoundError());
        }

        List<String> prompts = textParts(request.path("params").path("message").path("parts"));
        if (prompts.isEmpty()) {
            return new SendMessageResponse(id, new InvalidRequestError("message parts must contain text"));
        }

        Message response = new Message.Builder()
                .role(Message.Role.AGENT)
                .parts(new TextPart(creativeWriterService.writeDraft(prompts)))
                .metadata(Map.of("provider", "other-agents"))
                .build();
        return new SendMessageResponse(id, response);
    }

    private String normalizedBaseUrl() {
        String publicUrl = properties.getPublicUrl();
        if (publicUrl == null || publicUrl.isBlank()) {
            return "http://localhost:8082";
        }
        return publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
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
}
