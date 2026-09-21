package com.h.backend.chat.infrastructure.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BankToolTest {

    @Test
    void evaluationCanResetAndInspectTheSameToolState() {
        Agents.BankTool tool = new Agents.BankTool();

        tool.resetToSingleAccount("eval-alice", 100D);
        tool.withdraw("eval-alice", 25D);

        assertEquals(75D, tool.snapshot().get("eval-alice"));
        assertEquals(1, tool.toolWriteCount());

        tool.clearAccounts();
        assertEquals(0, tool.snapshot().size());
        assertEquals(0, tool.toolWriteCount());
    }
}
