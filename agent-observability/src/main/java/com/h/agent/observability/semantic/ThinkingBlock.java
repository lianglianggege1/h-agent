package com.h.agent.observability.semantic;

public record ThinkingBlock(String thinking) implements SemanticBlock {

    @Override
    public String type() {
        return "thinking";
    }
}
