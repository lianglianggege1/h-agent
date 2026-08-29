package com.h.backend.observability.a2a;

import com.h.agent.observability.AgentObservability;
import org.a2aproject.sdk.client.http.A2AHttpClient;
import org.a2aproject.sdk.client.http.A2AHttpClientProvider;
import org.a2aproject.sdk.client.http.JdkA2AHttpClientProvider;

/**
 * 通过 A2A SDK 的 ServiceLoader 接缝安装观测 HTTP 客户端（设计 13.2 的项目侧实现）：
 * langchain4j {@code AgenticServices.a2aBuilder} 不透出 Transport 配置，而
 * {@code A2AHttpClientFactory} 按 priority 选择最高优先级 provider，因此以高优先级
 * provider 包装默认 JDK 客户端，在真实请求创建时注入 W3C Context。
 * <p>
 * 必须在创建任何 A2A 远程代理之前调用 {@link #initialize(AgentObservability)}
 * （由 {@code A2AAgentConfig} 在构建代理前完成）；未初始化时返回原始客户端，行为不变。
 */
public final class ObservingA2AHttpClientProvider implements A2AHttpClientProvider {

    private static volatile AgentObservability observability;

    public static void initialize(AgentObservability instance) {
        observability = instance;
    }

    static AgentObservability currentObservability() {
        return observability;
    }

    @Override
    public A2AHttpClient create() {
        A2AHttpClient delegate = new JdkA2AHttpClientProvider().create();
        AgentObservability current = observability;
        return current == null ? delegate : new ObservingA2AHttpClient(current, delegate);
    }

    @Override
    public int priority() {
        return 1000;
    }

    @Override
    public String name() {
        return "h-agent-observing";
    }
}
