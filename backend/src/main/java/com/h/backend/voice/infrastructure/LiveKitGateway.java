package com.h.backend.voice.infrastructure;

import com.h.backend.voice.domain.VoiceCall;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

@Component
public class LiveKitGateway {
    private final VoiceProperties config;
    private final ObjectMapper json;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public LiveKitGateway(VoiceProperties config, ObjectMapper json) { this.config = config; this.json = json; }

    public String participantToken(VoiceCall call) {
        return token(call.getParticipantIdentity(), Map.of("room", call.getRoomName(), "roomJoin", true,
                "canPublish", true, "canSubscribe", true, "canPublishData", false,
                "canPublishSources", List.of("microphone")), 300);
    }
    private String token(String subject, Map<String, Object> grants, int seconds) {
        long now = System.currentTimeMillis();
        return Jwts.builder().issuer(config.getLivekitApiKey()).subject(subject)
                .issuedAt(new Date(now)).notBefore(new Date(now - 10_000)).expiration(new Date(now + seconds * 1000L))
                .claim("video", grants).signWith(Keys.hmacShaKeyFor(config.getLivekitApiSecret().getBytes(StandardCharsets.UTF_8)), Jwts.SIG.HS256).compact();
    }
    public String dispatch(VoiceCall call) {
        // Query before creating: a timed-out earlier request may already have created the dispatch.
        var existing = rpc("AgentDispatchService/ListDispatch", call.getRoomName(), Map.of("room", call.getRoomName()));
        for (var d : existing.path("agentDispatches")) if (config.getAgentName().equals(d.path("agentName").asText())) return d.path("id").asText();
        for (var d : existing.path("agent_dispatches")) if (config.getAgentName().equals(d.path("agent_name").asText())) return d.path("id").asText();
        var response = rpc("AgentDispatchService/CreateDispatch", call.getRoomName(), Map.of(
                "room", call.getRoomName(), "agent_name", config.getAgentName(),
                "metadata", json.writeValueAsString(Map.of("callId", call.getId(), "claimSecret", call.getClaimSecret()))));
        String id = response.path("id").asText();
        if (id.isBlank()) throw new IllegalStateException("LiveKit dispatch did not return an ID");
        return id;
    }
    public void deleteRoom(String room) { rpc("RoomService/DeleteRoom", room, Map.of("room", room)); }
    public boolean participantPresent(VoiceCall call) {
        var response = rpc("RoomService/ListParticipants", call.getRoomName(), Map.of("room", call.getRoomName()));
        for (var p : response.path("participants")) if (call.getParticipantIdentity().equals(p.path("identity").asText())) return true;
        return false;
    }
    private JsonNode rpc(String method, String room, Object body) {
        try {
            var req = HttpRequest.newBuilder(URI.create(config.getLivekitApiUrl().replaceAll("/$", "") + "/twirp/livekit." + method))
                    .timeout(Duration.ofSeconds(8)).header("Authorization", "Bearer " + token("voice-control", Map.of("room", room, "roomAdmin", true, "roomCreate", true, "roomList", true), 60))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
            var result = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (result.statusCode() == 404 && (method.endsWith("DeleteRoom") || method.endsWith("ListDispatch") || method.endsWith("ListParticipants"))) return json.createObjectNode();
            if (result.statusCode() / 100 != 2) throw new IllegalStateException("LiveKit HTTP " + result.statusCode());
            return json.readTree(result.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("LiveKit request interrupted", e);
        } catch (java.io.IOException e) { throw new IllegalStateException("LiveKit unavailable", e); }
    }
    public JsonNode verifyWebhook(String authorization, String body) {
        try {
            String raw = authorization != null && authorization.startsWith("Bearer ") ? authorization.substring(7) : authorization;
            var claims = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(config.getLivekitApiSecret().getBytes(StandardCharsets.UTF_8)))
                    .requireIssuer(config.getLivekitApiKey()).build().parseSignedClaims(raw).getPayload();
            if (claims.getExpiration() == null) throw new IllegalArgumentException("Missing expiry");
            byte[] supplied = Base64.getDecoder().decode(claims.get("sha256", String.class));
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8));
            if (!MessageDigest.isEqual(supplied, actual)) throw new IllegalArgumentException("Invalid body digest");
            return json.readTree(body);
        } catch (Exception e) { throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Invalid LiveKit webhook"); }
    }
}
