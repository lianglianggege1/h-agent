package com.h.backend.chat.domain.agent;

public final class ChatAgentIds {

    public static final String STANDARD_CHAT = "standard-chat";
    public static final String HARNESS = "harness-agent";
    public static final String ORDER_PROCESSING = "order-processing-assistant";

    public static boolean isHarness(String agentId) {
        return HARNESS.equals(agentId) || ORDER_PROCESSING.equals(agentId);
    }

    private ChatAgentIds() {
    }
}
