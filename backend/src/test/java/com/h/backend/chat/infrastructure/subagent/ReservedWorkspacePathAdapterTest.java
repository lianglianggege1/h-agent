package com.h.backend.chat.infrastructure.subagent;

import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.harness.agent.filesystem.AbstractFilesystem;
import io.agentscope.harness.agent.filesystem.model.FileInfo;
import io.agentscope.harness.agent.filesystem.model.GlobResult;
import io.agentscope.harness.agent.filesystem.model.LsResult;
import io.agentscope.harness.agent.filesystem.model.ReadResult;
import io.agentscope.harness.agent.filesystem.model.WriteResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 保留路径约束（设计 7.3）：subagents/ 目录对 scanner 不可见、对写工具明确报错，
 * 其余路径完全透传。
 */
class ReservedWorkspacePathAdapterTest {

    private final AbstractFilesystem delegate = mock(AbstractFilesystem.class);
    private final ReservedWorkspacePathAdapter adapter =
            new ReservedWorkspacePathAdapter(delegate);

    @Test
    void writeIntoReservedDirFailsWithExplicitError() {
        WriteResult result = adapter.write(RuntimeContext.empty(), "subagents/my-agent.md", "body");

        assertTrue(result.error() != null && result.error().contains("subagents/"));
        verify(delegate, never()).write(any(), any(), any());
    }

    @Test
    void readFromReservedDirFails() {
        when(delegate.read(any(), any(), anyInt(), anyInt())).thenThrow(new AssertionError());

        ReadResult result = adapter.read(RuntimeContext.empty(), "subagents/my-agent.md", 0, 10);
        assertTrue(result.error() != null);
    }

    @Test
    void normalizedReservedPathsAreBlocked() {
        WriteResult backslash = adapter.write(RuntimeContext.empty(), "subagents\\x.md", "b");
        WriteResult leadingSlash = adapter.write(RuntimeContext.empty(), "/subagents/x.md", "b");
        WriteResult dotSlash = adapter.write(RuntimeContext.empty(), "./subagents/x.md", "b");

        assertTrue(backslash.error() != null);
        assertTrue(leadingSlash.error() != null);
        assertTrue(dotSlash.error() != null);
        verify(delegate, never()).write(any(), any(), any());
    }

    @Test
    void reservedDirIsInvisibleToLsGrepExists() {
        LsResult ls = adapter.ls(RuntimeContext.empty(), "subagents");
        assertTrue(ls.entries().isEmpty());

        assertTrue(adapter.grep(RuntimeContext.empty(), "pattern", "subagents", "*.md")
                .matches().isEmpty());
        assertFalse(adapter.exists(RuntimeContext.empty(), "subagents/my-agent.md"));
        assertFalse(adapter.exists(RuntimeContext.empty(), "subagents"));

        verify(delegate, never()).ls(any(), any());
        verify(delegate, never()).exists(any(), any());
    }

    @Test
    void recursiveGlobFiltersReservedMatches() {
        when(delegate.glob(any(), any(), any())).thenReturn(GlobResult.success(List.of(
                new FileInfo("notes/a.md", false, 1, null),
                new FileInfo("subagents/hidden.md", false, 1, null),
                new FileInfo("subagents/other.md", false, 1, null)
        )));

        GlobResult result = adapter.glob(RuntimeContext.empty(), "**/*.md", "");

        assertTrue(result.isSuccess());
        assertEquals(List.of("notes/a.md"), result.matches().stream().map(FileInfo::path).toList());
    }

    @Test
    void normalPathsDelegateUntouched() {
        when(delegate.write(any(), any(), any())).thenReturn(WriteResult.ok("README.md"));
        when(delegate.ls(any(), any())).thenReturn(LsResult.success(List.of(
                new FileInfo("README.md", false, 1, null))));
        when(delegate.exists(any(), any())).thenReturn(true);

        assertEquals(WriteResult.ok("README.md"), adapter.write(RuntimeContext.empty(), "README.md", "hi"));
        assertEquals(1, adapter.ls(RuntimeContext.empty(), "").entries().size());
        assertTrue(adapter.exists(RuntimeContext.empty(), "README.md"));

        verify(delegate).write(any(), eq("README.md"), eq("hi"));
        assertNull(adapter.ls(RuntimeContext.empty(), "").error());
    }
}
