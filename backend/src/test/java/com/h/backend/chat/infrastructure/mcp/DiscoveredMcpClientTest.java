package com.h.backend.chat.infrastructure.mcp;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.junit.jupiter.api.Test;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscoveredMcpClientTest {
    @Test
    void reusesSessionAndDrainsAsyncCallBeforeClosingRetiredClient() throws Exception {
        var address = new AtomicReference<>("first");
        var first = mock(McpClient.class); var second = mock(McpClient.class);
        var request = ToolExecutionRequest.builder().name("command").arguments("{}").build();
        var context = InvocationContext.builder().build();
        var pending = new CompletableFuture<ToolExecutionResult>();
        when(first.executeToolAsync(request, context)).thenReturn(pending);
        @SuppressWarnings("unchecked") Function<String,McpClient> factory = mock(Function.class);
        when(factory.apply("first")).thenReturn(first); when(factory.apply("second")).thenReturn(second);
        var client = DiscoveredMcpClient.create("orders", previous -> address.get(), factory);
        assertEquals("orders", client.key());
        var result = client.executeToolAsync(request, context);
        client.listTools(); client.listTools();
        verify(factory, times(1)).apply("first");
        address.set("second"); client.listTools();
        verify(first, never()).close();
        pending.complete(ToolExecutionResult.builder().resultText("ok").build());
        assertEquals("ok", result.join().resultText());
        verify(first).close();
        client.close(); client.close(); verify(second).close();
        assertThrows(IllegalStateException.class, client::listTools);
    }

    @Test
    void neverReplaysFailedCommandAndDoesNotFallBackWhenDiscoveryFails() throws Exception {
        var target = mock(McpClient.class);
        var address = new AtomicReference<>("first");
        var client = DiscoveredMcpClient.create("orders", preferred -> {
            if (address.get() == null) throw new IllegalStateException("No endpoint");
            return address.get();
        }, ignored -> target);
        var command = ToolExecutionRequest.builder().name("command").arguments("{}").build();
        var failure = new IllegalStateException("response lost");
        when(target.executeTool(command)).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> client.executeTool(command)));
        verify(target, times(1)).executeTool(command);
        address.set(null);
        assertThrows(IllegalStateException.class, client::listTools);
        verify(target, never()).listTools();
        client.close();
    }
}
