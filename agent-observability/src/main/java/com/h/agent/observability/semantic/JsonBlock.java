package com.h.agent.observability.semantic;

public record JsonBlock(String json) implements SemanticBlock {

    @Override
    public String type() {
        return "json";
    }
}
