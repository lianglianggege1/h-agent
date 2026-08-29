package com.h.agent.observability.semantic;

public sealed interface SemanticBlock
        permits TextBlock, ThinkingBlock, JsonBlock, ToolCallBlock, ToolResultBlock,
        ArtifactReferenceBlock, ProviderExtensionBlock {

    String type();
}
