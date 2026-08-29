package com.h.otheragents.observability;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.agent.observability.lifecycle.ObservationScope;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.TextBlock;
import com.h.agent.observability.semantic.ToolResultBlock;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.McpSyncServerExchange;
import org.springframework.ai.mcp.McpToolUtils;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * MCP 服务端 Tool Observation（设计 14.2 {@code ObservedToolCallbackProvider}）：
 * 把真实 Spring AI ToolCallback 转换为带观测的 AsyncToolSpecification，工具执行
 * 外创建 server-side {@code tool <name>} Observation。
 * <p>
 * 父级经 {@code exchange.transportContext()}（由
 * {@code ObservingMcpTransportContextExtractor} 填充）取得当次 POST 的 SERVER
 * remote_call——回调在 boundedElastic 线程执行，线程切换后因果不丢。真实同步
 * 调用包裹在 Observation scope 内执行，工具内部产生的子 Observation 亦正确挂靠；
 * 完成、异常或取消时恰好结束一次，原始结果与异常语义不变。
 */
public final class ObservedMcpToolSpecifications {

    private static final ObjectMapper JSON = new ObjectMapper();

    private ObservedMcpToolSpecifications() {
    }

    public static List<McpServerFeatures.AsyncToolSpecification> observing(
            AgentObservability observability, ToolCallback[] toolCallbacks) {
        List<McpServerFeatures.AsyncToolSpecification> specifications = new ArrayList<>();
        for (ToolCallback toolCallback : toolCallbacks) {
            specifications.add(observing(observability, McpToolUtils.toSyncToolSpecification(toolCallback)));
        }
        return specifications;
    }

    public static McpServerFeatures.AsyncToolSpecification observing(
            AgentObservability observability, McpServerFeatures.SyncToolSpecification sync) {
        String toolName = sync.tool().name();
        return McpServerFeatures.AsyncToolSpecification.builder()
                .tool(sync.tool())
                .callHandler((exchange, request) -> {
                    AgentObservation tool = observability.span(
                            ObservationSpec.of("tool " + toolName, HObsKind.TOOL, "mcp-server",
                                    Map.of(HAttrs.TOOL_NAME, toolName)),
                            parentOf(observability, exchange.transportContext()));
                    tool.input(SemanticContent.ofBlocks(List.of(
                            new TextBlock(toJson(request.arguments())))));
                    return Mono.fromCallable(() -> {
                                try (ObservationScope scope = observability.scope(tool.context())) {
                                    return sync.callHandler().apply(new McpSyncServerExchange(exchange), request);
                                }
                            })
                            .subscribeOn(Schedulers.boundedElastic())
                            .doOnSuccess(result -> {
                                tool.output(SemanticContent.ofBlocks(List.of(
                                        new ToolResultBlock(null, toolName, toJson(result.content()),
                                                Boolean.TRUE.equals(result.isError())))));
                                if (Boolean.TRUE.equals(result.isError())) {
                                    tool.fail(new IllegalStateException("MCP tool reported an error result"));
                                } else {
                                    tool.succeed();
                                }
                            })
                            .doOnError(tool::fail)
                            .doOnCancel(() -> tool.cancel("tool call cancelled"));
                })
                .build();
    }

    private static ObservationContext parentOf(AgentObservability observability, McpTransportContext transportContext) {
        Object parent = transportContext == null ? null : transportContext.get(McpObservability.TRANSPORT_METADATA_KEY);
        if (parent instanceof ObservationContext observationContext) {
            return observationContext;
        }
        return observability.currentContext();
    }

    private static String toJson(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (Exception exception) {
            return String.valueOf(value);
        }
    }
}
