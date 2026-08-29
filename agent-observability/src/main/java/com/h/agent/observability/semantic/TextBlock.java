package com.h.agent.observability.semantic;

public record TextBlock(String text) implements SemanticBlock {

    @Override
    public String type() {
        return "text";
    }
}
