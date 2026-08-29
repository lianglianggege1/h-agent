package com.h.agent.observability.semantic;

public record ProviderExtensionBlock(String provider, String json) implements SemanticBlock {

    @Override
    public String type() {
        return "provider_extension";
    }
}
