package com.h.backend.document.infrastructure.a2a;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Set;

@RestController
@ConditionalOnProperty(prefix = "agents.a2a.other-agents", name = {"enabled", "documents.enabled"}, havingValue = "true")
public class A2ADocumentNotifications {
    private static final Logger log = LoggerFactory.getLogger(A2ADocumentNotifications.class);
    public static final String PATH = "/api/internal/a2a/document-updates";
    private final JdbcClient jdbc;
    private final io.jsonwebtoken.JwtParser auth;
    private final ObjectMapper json = new ObjectMapper();

    public A2ADocumentNotifications(DataSource source,
            @Value("${agents.a2a.other-agents.story.signing-key}") String secret) {
        jdbc = JdbcClient.create(source);
        auth = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
                .requireIssuer("h-agent").requireAudience(A2ADocumentDispatch.CALLBACK_AUDIENCE).build();
    }

    @PostMapping(PATH)
    public ResponseEntity<Void> receive(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
                                        String authorization, @RequestBody String body) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return ResponseEntity.status(401).build();
        }
        if (body.length() > 16_384) return ResponseEntity.badRequest().build();
        try {
            var identity = auth.parseSignedClaims(authorization.substring(7)).getPayload();
            if (identity.getExpiration() == null) return ResponseEntity.status(401).build();
            JsonNode status = json.readTree(body).path("statusUpdate");
            String id = status.path("taskId").asText();
            if (!id.equals(identity.get("task_id", String.class))) return ResponseEntity.status(403).build();
            long owner = Long.parseLong(identity.getSubject());
            long revision = status.at("/metadata/revision").asLong(-1);
            String state = status.at("/status/state").asText();
            if (revision < 0 || !Set.of("TASK_STATE_COMPLETED", "TASK_STATE_FAILED", "TASK_STATE_CANCELED",
                    "TASK_STATE_INPUT_REQUIRED").contains(state)) return ResponseEntity.badRequest().build();
            jdbc.sql("""
                    INSERT INTO a2a_document_notifications (task_id,user_id,revision,state)
                    SELECT id,user_id,?,? FROM chat_document_tasks
                    WHERE user_id=? AND id=? AND revision>=?
                    ON CONFLICT (task_id) DO UPDATE SET revision=EXCLUDED.revision, state=EXCLUDED.state, received_at=NOW()
                    WHERE a2a_document_notifications.revision < EXCLUDED.revision
                    """).params(revision, state, owner, id, revision).update();
            log.info("A2A document notification received taskId={} revision={} state={}", id, revision, state);
            return ResponseEntity.noContent().build();
        } catch (JwtException | IllegalArgumentException failure) {
            return ResponseEntity.status(401).build();
        } catch (com.fasterxml.jackson.core.JsonProcessingException failure) {
            return ResponseEntity.badRequest().build();
        }
    }
}
