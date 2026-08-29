package com.h.agent.observability.semantic;

public record ToolCallBlock(String id, String name, String argumentsJson) implements SemanticBlock {

    @Override
    public String type() {
        return "tool_call";
    }
}
