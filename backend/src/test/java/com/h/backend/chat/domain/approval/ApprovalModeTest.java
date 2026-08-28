package com.h.backend.chat.domain.approval;

import com.h.backend.chat.domain.agent.ChatAgentIds;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ApprovalModeTest {

    @Test
    void keepsMissingModeBackwardCompatibleForHarnessSessions() {
        assertEquals(
                ApprovalMode.BYPASS,
                ApprovalMode.resolveForNewSession(ChatAgentIds.HARNESS, null)
        );
    }

    @Test
    void keepsExplicitHarnessMode() {
        assertEquals(
                ApprovalMode.ACCEPT_EDITS,
                ApprovalMode.resolveForNewSession(
                        ChatAgentIds.HARNESS,
                        ApprovalMode.ACCEPT_EDITS
                )
        );
    }

    @Test
    void rejectsApprovalModeForNonHarnessAgents() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ApprovalMode.resolveForNewSession(
                        ChatAgentIds.STANDARD_CHAT,
                        ApprovalMode.BYPASS
                )
        );
    }

    @Test
    void leavesNonHarnessSessionsWithoutApprovalMode() {
        assertNull(ApprovalMode.resolveForNewSession(ChatAgentIds.STANDARD_CHAT, null));
    }
}
