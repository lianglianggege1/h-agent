package com.h.otheragents.a2a.story;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.agent.observability.lifecycle.ExecutionContextSnapshot;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.otheragents.observability.A2AObservabilityWebFilter;
import com.h.capabilities.chat.infrastructure.config.ImageGenerationProperties;
import com.h.capabilities.chat.infrastructure.image.MiniMaxHttpImageClient;
import com.h.capabilities.chat.infrastructure.image.MiniMaxImageClient;
import com.h.capabilities.chat.infrastructure.image.MiniMaxImageGenerationRequest;
import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.a2aproject.sdk.spec.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@ConditionalOnProperty(prefix = "other-agents.a2a.story", name = "enabled", havingValue = "true")
public final class StoryIllustratorController {
    public static final String ID = "story-illustrator";
    private static final String PATH = "/a2a/agents/" + ID;
    private static final ObjectMapper JSON = new ObjectMapper();
    private final OtherAgentsA2AProperties properties;
    private final ImageGenerationProperties.MiniMax settings;
    private final MiniMaxImageClient images;
    private final io.jsonwebtoken.JwtParser auth;

    public StoryIllustratorController(OtherAgentsA2AProperties properties, Environment environment,
            tools.jackson.databind.ObjectMapper providerJson, @Value("${other-agents.a2a.story.signing-key}") String secret) {
        this.properties = properties;
        var config = Binder.get(environment).bind("image-generation", ImageGenerationProperties.class)
                .orElseGet(() -> new ImageGenerationProperties(null));
        settings = config.minimaxOrDefault();
        images = new MiniMaxHttpImageClient(config, providerJson);
        auth = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .requireIssuer("h-agent").requireAudience(ID).build();
    }

    @GetMapping(value = PATH + "/.well-known/agent-card.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public String publicCard() throws Exception { return JSON.writeValueAsString(card()); }

    public AgentCard card() {
        String address = properties.agentUrl(ID);
        return AgentCard.builder().name(ID).description("根据故事文本创作插画").version(properties.getVersion())
                .url(address).supportedInterfaces(List.of(new AgentInterface("JSONRPC", address)))
                .capabilities(AgentCapabilities.builder().build())
                .securitySchemes(Map.of("bearer", new HTTPAuthSecurityScheme("JWT", "Bearer", null)))
                .securityRequirements(List.of(new SecurityRequirement(Map.of("bearer", List.of()))))
                .defaultInputModes(List.of("text/plain")).defaultOutputModes(List.of("image/png", "image/jpeg", "image/webp"))
                .skills(List.of(AgentSkill.builder().id("illustrate-story").name("故事插画")
                        .description("交付故事插画文件").tags(List.of("image", "illustration")).build())).build();
    }

    @PostMapping(value = PATH, produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<String>> illustrate(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                                                   String header, @RequestBody String body,
                                               @RequestAttribute(name = A2AObservabilityWebFilter.EXCHANGE_ATTRIBUTE, required = false)
                                               ObservationContext observation) {
        if (!authorized(header)) return Mono.just(ResponseEntity.status(401).build());
        return Mono.fromCallable(() -> {
            try (var scope = ExecutionContextSnapshot.forObservation(observation).scope()) {
                return ResponseEntity.ok(handle(body));
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private boolean authorized(String header) {
        if (header == null || !header.startsWith("Bearer ")) return false;
        try {
            var claimant = auth.parseSignedClaims(header.substring(7)).getPayload();
            return claimant.getExpiration() != null && claimant.getSubject() != null
                    && claimant.getSubject().matches("[1-9][0-9]{0,18}");
        } catch (JwtException | IllegalArgumentException failure) {
            return false;
        }
    }

    private String handle(String payload) {
        var response = JSON.createObjectNode().put("jsonrpc", "2.0");
        try {
            var request = JSON.readTree(payload);
            response.set("id", request.has("id") ? request.get("id") : JSON.nullNode());
            String prompt = request.at("/params/message/parts/0/text").asText();
            if (!"SendMessage".equals(request.path("method").asText()) || prompt.isBlank() || prompt.length() > 10000) {
                response.putObject("error").put("code", -32602).put("message", "Invalid illustration request");
                return response.toString();
            }
            var generated = images.generate(new MiniMaxImageGenerationRequest(settings.model(),
                    "为这篇故事创作一幅插画：\n" + prompt, settings.aspectRatio(), "url", 1,
                    settings.promptOptimizer(), null));
            var image = generated.images().getFirst();
            if (image.mimeType() == null || !Set.of("image/jpeg", "image/png", "image/webp").contains(image.mimeType())
                    || image.imageBytes() == null || image.imageBytes().length > 10 * 1024 * 1024) {
                throw new IllegalStateException("Invalid illustration file");
            }
            var task = response.putObject("result").putObject("task")
                    .put("id", UUID.randomUUID().toString()).put("contextId", UUID.randomUUID().toString());
            task.putObject("status").put("state", "TASK_STATE_COMPLETED");
            task.putArray("artifacts").addObject().put("artifactId", UUID.randomUUID().toString())
                    .putArray("parts").addObject().put("mediaType", image.mimeType())
                    .put("filename", "story-illustration." + image.mimeType().substring(6)).put("raw", image.imageBytes());
        } catch (Exception failure) {
            response.remove("result");
            response.putObject("error").put("code", -32603).put("message", "Illustration generation failed");
        }
        return response.toString();
    }
}
