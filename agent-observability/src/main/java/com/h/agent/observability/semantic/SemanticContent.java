package com.h.agent.observability.semantic;

import java.util.List;

public record SemanticContent(
        List<SemanticMessage> messages,
        List<SemanticBlock> blocks,
        ContentCaptureState captureState
) {

    public static SemanticContent empty() {
        return new SemanticContent(List.of(), List.of(), ContentCaptureState.SOURCE_UNAVAILABLE);
    }

    public static SemanticContent ofMessages(List<SemanticMessage> messages) {
        return new SemanticContent(messages, List.of(), ContentCaptureState.INLINE);
    }

    public static SemanticContent ofBlocks(List<SemanticBlock> blocks) {
        return new SemanticContent(List.of(), blocks, ContentCaptureState.INLINE);
    }
}
