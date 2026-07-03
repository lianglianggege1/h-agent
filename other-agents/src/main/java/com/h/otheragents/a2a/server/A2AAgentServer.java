package com.h.otheragents.a2a.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.h.otheragents.a2a.config.OtherAgentsA2AProperties;
import com.h.otheragents.a2a.export.A2AAgentExportRegistry;
import org.a2aproject.sdk.spec.AgentCard;

public class A2AAgentServer {

    private final A2AAgentExportRegistry registry;
    private final A2AAgentCardFactory cardFactory;
    private final JsonRpcA2ATransportWrapper transportWrapper;

    private A2AAgentServer(
            A2AAgentExportRegistry registry,
            A2AAgentCardFactory cardFactory,
            JsonRpcA2ATransportWrapper transportWrapper
    ) {
        this.registry = registry;
        this.cardFactory = cardFactory;
        this.transportWrapper = transportWrapper;
    }

    public static A2AAgentServer create(
            OtherAgentsA2AProperties properties,
            A2AAgentExportRegistry registry,
            LangChain4jAgentMethodInvoker methodInvoker,
            A2AMessageMapper messageMapper,
            A2ATaskStore taskStore
    ) {
        A2AAgentExecutor executor = new A2AAgentExecutor(registry, methodInvoker, messageMapper, taskStore);
        return new A2AAgentServer(
                registry,
                new A2AAgentCardFactory(properties),
                new JsonRpcA2ATransportWrapper(A2ARequestHandler.messageSend(executor))
        );
    }

    public AgentCard card(String agentId) {
        return cardFactory.card(registry.require(agentId));
    }

    public JsonNode handle(String agentId, JsonNode request) {
        registry.require(agentId);
        return transportWrapper.handle(agentId, request);
    }
}
