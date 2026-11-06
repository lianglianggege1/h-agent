package com.h.backend.chat.infrastructure.config;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ToolSchema;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

/** Ends the current turn at document background/confirmation boundaries instead of model polling. */
final class HarnessToolContinuationPolicy {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> DOCUMENT_TOOLS = Set.of(
            "parse_document", "ocr_document", "review_ocr", "verify_ocr", "generate_document");
    private static final Set<String> STOP_STATES = Set.of(
            "PARSING", "QUEUED", "OCR_RUNNING", "VERIFYING", "GENERATING",
            "WAITING_CONFIRMATION", "VERIFICATION_FAILED", "FAILED", "CANCELLED",
            "BUDGET_EXHAUSTED", "CLOUD_UNAVAILABLE", "UNSUPPORTED", "ENCRYPTED");

    static boolean endTurn(List<Msg> messages, List<ToolSchema> tools) {
        // SDK max-iteration summaries and memory calls carry no tool schemas. Explicitly forbid
        // the provider from inventing a tool call based on names in conversation history.
        if (tools == null || tools.isEmpty()) return true;
        if (messages == null || messages.isEmpty()) return false;
        Msg last = messages.getLast();
        if (last.getRole() != MsgRole.TOOL) return false;
        for (ToolResultBlock result : last.getContentBlocks(ToolResultBlock.class)) {
            if (result.getName() == null || !DOCUMENT_TOOLS.contains(result.getName())) continue;
            for (var output : result.getOutput()) {
                if (!(output instanceof TextBlock text)) continue;
                try {
                    var state = JSON.readTree(text.getText());
                    if (state.hasNonNull("taskId") && STOP_STATES.contains(state.path("status").asText()))
                        return true;
                } catch (Exception notDocumentJson) {
                    // A tool may return ordinary text rather than a document status envelope.
                }
            }
        }
        return false;
    }
}
