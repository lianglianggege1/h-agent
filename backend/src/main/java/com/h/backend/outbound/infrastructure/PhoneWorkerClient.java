package com.h.backend.outbound.infrastructure;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Component
public class PhoneWorkerClient {
    private final String workerUrl;
    private final String internalToken;
    private final HttpClient client;
    private final ObjectMapper json;

    public PhoneWorkerClient(
            @Value("${outbound.worker-url:http://127.0.0.1:8082}") String workerUrl,
            @Value("${outbound.internal-token:}") String internalToken,
            ObjectMapper json) {
        this.workerUrl = workerUrl;
        this.internalToken = internalToken;
        this.json = json;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    /** The client only prepares or cleans up the remote Worker. Business resources are owned by OutboundModule. */
    public void prepare(PrepareRequest payload) {
        try {
            String body = json.writeValueAsString(payload);
            var request = HttpRequest.newBuilder(URI.create(workerUrl + "/prepare"))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + internalToken)
                    .timeout(Duration.ofSeconds(10))
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("Phone Worker prepare returned HTTP " + response.statusCode());
            }
            PrepareResponse prepared = json.readValue(response.body(), PrepareResponse.class);
            if (!"ready".equals(prepared.status()) || !prepared.playoutAcknowledgements()) {
                throw new IllegalStateException("Phone Worker did not confirm media playout capability");
            }
            log.info("[PhoneWorker] Prepared callId={} agentId={}", payload.callId(), payload.agentId());
        } catch (JacksonException e) {
            throw new IllegalStateException("Cannot encode Phone Worker preparation", e);
        } catch (Exception e) {
            throw new IllegalStateException("Phone Worker preparation failed for " + payload.callId(), e);
        }
    }

    public void cleanup(String callId) {
        try {
            var request = HttpRequest.newBuilder(URI.create(workerUrl + "/cleanup/" + callId))
                    .header("Authorization", "Bearer " + internalToken)
                    .timeout(Duration.ofSeconds(5))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .build();
            client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("[PhoneWorker] Cleanup notification failed callId={}: {}", callId, e.getMessage());
        }
    }

    public record PrepareRequest(
            String callId, String extension, String domain, String agentId,
            String claimSecret, String sessionId, Long promptId, String communicationGoal) {}
    private record PrepareResponse(String status, String callId, boolean playoutAcknowledgements) {}
}
