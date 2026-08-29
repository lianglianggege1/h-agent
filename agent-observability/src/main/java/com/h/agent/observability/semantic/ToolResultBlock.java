package com.h.agent.observability.semantic;

public record ToolResultBlock(String id, String name, String contentJson, boolean error) implements SemanticBlock {

    @Override
    public String type() {
        return "tool_result";
    }
}
