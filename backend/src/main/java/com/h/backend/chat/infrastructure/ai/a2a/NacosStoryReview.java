package com.h.backend.chat.infrastructure.ai.a2a;

import com.alibaba.nacos.api.PropertyKeyConst;
import com.alibaba.nacos.api.ai.AiService;
import com.alibaba.nacos.api.ai.model.a2a.AgentCardDetailInfo;
import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.maintainer.client.ai.A2aMaintainerService;
import com.alibaba.nacos.maintainer.client.ai.AiMaintainerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.registry.NacosA2AConstants;
import com.h.registry.NacosRegistryProperties;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;

final class NacosStoryReview {
    private final AiService runtime;
    private final NacosRegistryProperties properties;
    private final AgentObservability observability;
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private volatile A2aMaintainerService catalog;

    NacosStoryReview(AiService runtime, NacosRegistryProperties properties, AgentObservability observability) {
        this.runtime = runtime;
        this.properties = properties;
        this.observability = observability;
    }

    String run(String manuscript, String skill) {
        var span = observability.span(ObservationSpec.of("remote_call a2a.story-review", HObsKind.REMOTE_CALL,
                "a2a-client", Map.of("agent.skill", skill)), observability.currentContext());
        try {
            URI endpoint = discover(skill);
            if (endpoint == null) {
                span.fail(new IllegalStateException("No live review skill"));
                return "暂时找不到支持该审稿技能且在线的 Agent。";
            }
            String id = UUID.randomUUID().toString();
            var rpc = json.createObjectNode().put("jsonrpc", "2.0").put("id", id).put("method", "SendMessage");
            var message = rpc.putObject("params").putObject("message").put("messageId", id).put("role", "ROLE_USER");
            var parts = message.putArray("parts");
            parts.addObject().put("text", manuscript);
            parts.addObject().put("text", skill);
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofMinutes(4))
                    .header("A2A-Version", "1.0").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(rpc.toString()));
            Map<String, String> headers = new HashMap<>();
            observability.inject(span.context(), headers);
            headers.forEach(request::header);
            var reply = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            var task = json.readTree(reply.body()).at("/result/task");
            String text = task.at("/artifacts/0/parts/0/text").asText();
            if (reply.statusCode() != 200 || text.isBlank()
                    || !"TASK_STATE_COMPLETED".equals(task.at("/status/state").asText())) {
                throw new IllegalStateException("Review did not deliver a result");
            }
            span.attribute("a2a.task_id", task.path("id").asText());
            span.attribute("a2a.context_id", task.path("contextId").asText());
            span.succeed();
            return text;
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            span.fail(failure);
            return "远端审稿暂时失败，请稍后重试。";
        }
    }

    private URI discover(String skill) throws Exception {
        for (int page = 1; page <= 20; page++) {
            var result = catalog().listAgentCards(properties.namespace(), "SERVICE", page, 50);
            if (result == null || result.getPageItems() == null) return null;
            for (var listing : result.getPageItems()) {
                if (listing.getSkills() != null && !listing.getSkills().isEmpty()
                        && listing.getSkills().stream().noneMatch(candidate -> skill.equals(candidate.getId()))) continue;
                AgentCardDetailInfo card;
                try {
                    card = runtime.getAgentCard(listing.getName(), listing.getLatestPublishedVersion(), "SERVICE");
                } catch (NacosException unavailable) {
                    continue;
                }
                if (!offers(card, skill)) continue;
                String address = card.getUrl();
                if ((address == null || address.isBlank()) && card.getSupportedInterfaces() != null) {
                    address = card.getSupportedInterfaces().stream()
                            .filter(binding -> "JSONRPC".equals(binding.getProtocolBinding())
                                    || "JSONRPC".equals(binding.getTransport()))
                            .map(binding -> binding.getUrl()).filter(url -> url != null && !url.isBlank())
                            .findFirst().orElse(null);
                }
                if (address == null) continue;
                URI uri = URI.create(address);
                if (uri.getHost() != null && !NacosA2AConstants.UNRESOLVED_HOST.equals(uri.getHost())
                        && List.of("http", "https").contains(uri.getScheme()) && uri.getUserInfo() == null) {
                    return uri;
                }
            }
            if (page >= result.getPagesAvailable()) break;
        }
        return null;
    }

    private static boolean offers(AgentCardDetailInfo card, String skill) {
        return card != null && card.getSkills() != null && card.getSkills().stream().anyMatch(candidate ->
                skill.equals(candidate.getId()) && candidate.getTags() != null && candidate.getTags().contains("review")
                        && acceptsText(candidate.getInputModes(), card.getDefaultInputModes())
                        && acceptsText(candidate.getOutputModes(), card.getDefaultOutputModes()));
    }

    private static boolean acceptsText(List<String> skillModes, List<String> defaults) {
        var modes = skillModes == null || skillModes.isEmpty() ? defaults : skillModes;
        return modes != null && modes.contains("text/plain");
    }

    private synchronized A2aMaintainerService catalog() throws Exception {
        if (catalog == null) {
            var settings = new Properties();
            settings.setProperty(PropertyKeyConst.SERVER_ADDR, properties.serverAddr());
            settings.setProperty(PropertyKeyConst.NAMESPACE, properties.namespace());
            settings.setProperty(PropertyKeyConst.USERNAME, properties.username());
            settings.setProperty(PropertyKeyConst.PASSWORD, properties.password());
            catalog = AiMaintainerFactory.createAiMaintainerService(settings).a2a();
        }
        return catalog;
    }
}
