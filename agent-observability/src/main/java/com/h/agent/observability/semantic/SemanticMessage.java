package com.h.agent.observability.semantic;

import java.util.List;

public record SemanticMessage(String role, List<SemanticBlock> blocks) {

    public static SemanticMessage of(String role, String text) {
        return new SemanticMessage(role, List.of(new TextBlock(text)));
    }
}
