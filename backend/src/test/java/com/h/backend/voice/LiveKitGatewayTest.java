package com.h.backend.voice;
import com.h.backend.voice.domain.VoiceCall;
import com.h.backend.voice.infrastructure.*;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LiveKitGatewayTest {
    private VoiceProperties config() {
        var p=new VoiceProperties();p.setLivekitApiKey("test");p.setLivekitApiSecret("01234567890123456789012345678901");return p;
    }
    @Test void browserTokenOnlyPublishesMicrophoneIntoItsOwnRoom() {
        var p=config();var call=new VoiceCall();call.setRoomName("voice-room");call.setParticipantIdentity("caller");
        var token=new LiveKitGateway(p,new ObjectMapper()).participantToken(call);
        var claims=Jwts.parser().verifyWith(Keys.hmacShaKeyFor(p.getLivekitApiSecret().getBytes(StandardCharsets.UTF_8))).build().parseSignedClaims(token).getPayload();
        var video=(Map<?,?>)claims.get("video");
        assertEquals("voice-room",video.get("room"));assertEquals(false,video.get("canPublishData"));
        assertEquals(List.of("microphone"),video.get("canPublishSources"));
        assertNull(video.get("roomAdmin"));assertEquals("caller",claims.getSubject());
    }
    @Test void webhookRequiresSignatureAndExactBodyDigest() throws Exception {
        var p=config();var gateway=new LiveKitGateway(p,new ObjectMapper());String body="{\"event\":\"participant_joined\"}";
        String token=Jwts.builder().issuer("test").expiration(new Date(System.currentTimeMillis()+60000))
                .claim("sha256",Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(body.getBytes(StandardCharsets.UTF_8))))
                .signWith(Keys.hmacShaKeyFor(p.getLivekitApiSecret().getBytes(StandardCharsets.UTF_8))).compact();
        assertEquals("participant_joined",gateway.verifyWebhook(token,body).path("event").asText());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->gateway.verifyWebhook(token,body+" "));
    }
}
