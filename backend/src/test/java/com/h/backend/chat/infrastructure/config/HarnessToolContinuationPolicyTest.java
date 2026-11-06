package com.h.backend.chat.infrastructure.config;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.model.ToolSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class HarnessToolContinuationPolicyTest {
    final List<ToolSchema> tools = List.of(mock(ToolSchema.class));

    Msg result(String name, String state) {
        return Msg.builder().role(MsgRole.TOOL).content(ToolResultBlock.of("call1", name,
                TextBlock.builder().text("{\"taskId\":\"task1\",\"status\":\"" + state + "\"}").build())).build();
    }

    @Test
    void summaryWithToolHistoryMustStillDisableTools() {
        assertTrue(HarnessToolContinuationPolicy.endTurn(List.of(result("parse_document", "RECOGNIZED")), null));
        assertTrue(HarnessToolContinuationPolicy.endTurn(List.of(), List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PARSING", "QUEUED", "OCR_RUNNING", "VERIFYING", "WAITING_CONFIRMATION", "FAILED"})
    void pendingOrTerminalDocumentStopsModelPolling(String state) {
        assertTrue(HarnessToolContinuationPolicy.endTurn(List.of(result("parse_document", state)), tools));
    }

    @Test
    void completedRecognitionMayContinueUserRequestedWork() {
        assertFalse(HarnessToolContinuationPolicy.endTurn(List.of(result("parse_document", "RECOGNIZED")), tools));
    }

    @Test
    void oldPendingStateDoesNotRestrictANewUserTurn() {
        assertFalse(HarnessToolContinuationPolicy.endTurn(List.of(result("parse_document", "PARSING"),
                Msg.builder().role(MsgRole.USER).textContent("another request").build()), tools));
        assertFalse(HarnessToolContinuationPolicy.endTurn(List.of(result("unrelated_tool", "PARSING")), tools));
    }
}
