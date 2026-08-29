package com.h.backend.observability.mcp;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.lifecycle.ObservationScope;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.TextBlock;
import com.h.agent.observability.semantic.ToolCallBlock;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;

import java.util.List;
import java.util.Map;

/**
 * MCP 客户端 ToolExecutor 观测包装（设计 14.1）：每次真实工具执行外创建
 * {@code remote_call} CLIENT Span 并在其 scope 内执行委托，使 Transport 构造
 * POST 时（同一线程）读取到的 current context 即该 remote_call——
 * {@code ObservingMcpHeadersSupplier} 据此注入 W3C Header，服务端延续同一条 Trace。
 * <p>
 * 父级取调用线程上的 current context（Agent/tool Observation）；执行完成、异常
 * 后恢复父 context，保持原始业务异常与中断语义。
 */
public final class ObservingMcpToolExecutor implements ToolExecutor {

    private static final String SPAN_NAME = "remote_call mcp.tools/call";

    private final AgentObservability observability;
    private final ToolExecutor delegate;

    public ObservingMcpToolExecutor(AgentObservability observability, ToolExecutor delegate) {
        this.observability = observability;
        this.delegate = delegate;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        String toolName = request == null || request.name() == null ? "unknown" : request.name();
        AgentObservation span = observability.span(
                ObservationSpec.of(SPAN_NAME, HObsKind.REMOTE_CALL, "mcp-client",
                        Map.of(HAttrs.TOOL_NAME, toolName)),
                observability.currentContext());
        if (request != null) {
            span.input(SemanticContent.ofBlocks(List.of(
                    new ToolCallBlock(request.id(), toolName, request.arguments()))));
        }
        try (ObservationScope scope = observability.scope(span.context())) {
            String result = delegate.execute(request, memoryId);
            span.output(SemanticContent.ofBlocks(List.of(new TextBlock(String.valueOf(result)))));
            span.succeed();
            return result;
        } catch (RuntimeException | Error error) {
            span.fail(error);
            throw error;
        }
    }
}
