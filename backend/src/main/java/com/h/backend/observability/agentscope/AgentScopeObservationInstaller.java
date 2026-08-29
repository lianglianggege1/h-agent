package com.h.backend.observability.agentscope;

import com.h.agent.observability.AgentObservability;
import io.agentscope.core.ReActAgent;
import io.agentscope.harness.agent.HarnessAgent;
import org.springframework.stereotype.Component;

/**
 * AgentScope 观测统一安装入口（设计 12.3）。
 *
 * <p>所有 Agent 构建路径都必须经过它安装观测 middleware：</p>
 * <ul>
 *   <li>父 Harness Agent — {@code HarnessAgentConfig#harnessAgent}；</li>
 *   <li>SDK 静态子 Agent / 声明式子 Agent — 继承父 builder 的显式 middleware
 *       （SDK {@code filterCopyableMiddlewares} 复制非 HarnessRuntimeMiddleware）；</li>
 *   <li>Catalog USER 动态子 Agent — {@code AgentScopeSubagentRuntimeFactory#buildUserChild}；</li>
 *   <li>Gateway 独立子会话 — 复用父静态 factory 或 Catalog 物化产物，构建期同样经过本安装。</li>
 * </ul>
 *
 * <p>middleware 实现为普通 {@code MiddlewareBase}（非 {@code HarnessRuntimeMiddleware}），
 * 这是子 Agent 能继承它的前提；观测关闭时 middleware 内部直接透传，不改变任何执行语义。</p>
 */
@Component
public class AgentScopeObservationInstaller {

    private final AgentObservability observability;

    public AgentScopeObservationInstaller(AgentObservability observability) {
        this.observability = observability;
    }

    public HarnessAgent.Builder apply(HarnessAgent.Builder builder) {
        return builder.middleware(middleware());
    }

    public ReActAgent.Builder apply(ReActAgent.Builder builder) {
        return builder.middleware(middleware());
    }

    public HAgentObservabilityMiddleware middleware() {
        return new HAgentObservabilityMiddleware(observability);
    }
}
