package com.h.agent.observability.semantic;

public record ArtifactReferenceBlock(ArtifactReference reference) implements SemanticBlock {

    @Override
    public String type() {
        return "artifact";
    }
}
