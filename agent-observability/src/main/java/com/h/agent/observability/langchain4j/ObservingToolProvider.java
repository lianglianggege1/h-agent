package com.h.agent.observability.langchain4j;

import com.h.agent.observability.AgentObservability;
import com.h.agent.observability.AgentObservation;
import com.h.agent.observability.HAttrs;
import com.h.agent.observability.HObsKind;
import com.h.agent.observability.ObservationSpec;
import com.h.agent.observability.lifecycle.ObservationContext;
import com.h.agent.observability.semantic.ArtifactReference;
import com.h.agent.observability.semantic.ArtifactReferenceBlock;
import com.h.agent.observability.semantic.SemanticBlock;
import com.h.agent.observability.semantic.SemanticContent;
import com.h.agent.observability.semantic.ToolArtifactCollector;
import com.h.agent.observability.semantic.ToolCallBlock;
import com.h.agent.observability.semantic.ToolResultBlock;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Decorates a real ToolProvider: captures the observation context that is current when tools
 * are provided (request build time, running on the generation scope's thread) and wraps every
 * ToolExecutor with one tool observation per real execution. AiServiceTool metadata
 * (returnBehavior, immediateReturn) is preserved via toBuilder, so observation decoration never
 * changes the framework's tool return semantics.
 */
public final class ObservingToolProvider implements ToolProvider {

    private final ToolProvider delegate;
    private final AgentObservability observability;
    private final String runtime;

    public ObservingToolProvider(ToolProvider delegate, AgentObservability observability, String runtime) {
        this.delegate = delegate;
        this.observability = observability;
        this.runtime = runtime == null ? "langchain4j" : runtime;
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        ObservationContext parent = observability.currentContext();
        ToolProviderResult result = delegate.provideTools(request);
        if (result == null) {
            return null;
        }
        ToolProviderResult.Builder observed = ToolProviderResult.builder();
        for (AiServiceTool tool : result.aiServiceTools()) {
            ToolSpecification specification = tool.toolSpecification();
            observed.add(tool.toBuilder()
                    .toolExecutor(new ObservingToolExecutor(tool.toolExecutor(), specification, parent))
                    .build());
        }
        return observed.build();
    }

    @Override
    public boolean isDynamic() {
        return delegate.isDynamic();
    }

    private final class ObservingToolExecutor implements ToolExecutor {

        private final ToolExecutor delegate;
        private final ToolSpecification specification;
        private final ObservationContext parent;

        ObservingToolExecutor(ToolExecutor delegate, ToolSpecification specification, ObservationContext parent) {
            this.delegate = delegate;
            this.specification = specification;
            this.parent = parent;
        }

        @Override
        public String execute(ToolExecutionRequest request, Object memoryId) {
            AgentObservation observation = startTool(request);
            try {
                String result = delegate.execute(request, memoryId);
                observation.output(toolOutput(request, toolName(request), result, ToolArtifactCollector.drain()));
                observation.succeed();
                return result;
            } catch (RuntimeException ex) {
                ToolArtifactCollector.drain();
                observation.fail(ex);
                throw ex;
            } catch (Error ex) {
                ToolArtifactCollector.drain();
                observation.fail(ex);
                throw ex;
            }
        }

        @Override
        public dev.langchain4j.service.tool.ToolExecutionResult executeWithContext(
                ToolExecutionRequest request, dev.langchain4j.invocation.InvocationContext context) {
            AgentObservation observation = startTool(request);
            try {
                dev.langchain4j.service.tool.ToolExecutionResult result =
                        delegate.executeWithContext(request, context);
                observation.output(toolOutput(request, toolName(request),
                        result == null ? null : result.resultText(), ToolArtifactCollector.drain()));
                observation.succeed();
                return result;
            } catch (RuntimeException ex) {
                ToolArtifactCollector.drain();
                observation.fail(ex);
                throw ex;
            } catch (Error ex) {
                ToolArtifactCollector.drain();
                observation.fail(ex);
                throw ex;
            }
        }

        /**
         * 工具输出 = ToolResultBlock + 工具内已提交业务资源的 ArtifactReference
         * （设计 §9.9：Agent 交付文件在 Tool 输出记录 TOOL_OUTPUT 引用）。
         */
        private static SemanticContent toolOutput(
                ToolExecutionRequest request, String name, String resultText,
                List<ArtifactReference> artifacts) {
            List<SemanticBlock> blocks = new ArrayList<>(artifacts.size() + 1);
            blocks.add(new ToolResultBlock(request.id(), name, resultText, false));
            for (ArtifactReference artifact : artifacts) {
                blocks.add(new ArtifactReferenceBlock(artifact));
            }
            return SemanticContent.ofBlocks(blocks);
        }

        private AgentObservation startTool(ToolExecutionRequest request) {
            Map<String, String> attributes = new LinkedHashMap<>();
            attributes.put(HAttrs.TOOL_NAME, toolName(request));
            AgentObservation observation = observability.span(
                    ObservationSpec.of("tool." + toolName(request), HObsKind.TOOL, runtime, attributes),
                    parent);
            observation.input(SemanticContent.ofBlocks(List.of(
                    new ToolCallBlock(request.id(), toolName(request), request.arguments()))));
            return observation;
        }

        private String toolName(ToolExecutionRequest request) {
            if (request != null && request.name() != null) {
                return request.name();
            }
            return specification == null ? "unknown" : specification.name();
        }
    }
}
