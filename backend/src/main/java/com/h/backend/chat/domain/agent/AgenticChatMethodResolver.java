package com.h.backend.chat.domain.agent;

import dev.langchain4j.agentic.scope.ResultWithAgenticScope;
import dev.langchain4j.invocation.InvocationParameters;

import java.lang.reflect.Method;

/**
 * AGENTIC_SYNC 根 Agent 的调用约定解析：chat(String memoryId, String message, InvocationParameters)。
 * 启动期验证所有根 Agent；运行期按 bean class 缓存解析结果。
 */
public final class AgenticChatMethodResolver {

    public static final String CHAT_METHOD = "chat";

    private AgenticChatMethodResolver() {
    }

    public static Method requireChatMethod(Object agentBean) {
        if (agentBean == null) {
            throw new IllegalStateException("Unsupported AGENTIC_SYNC agent bean: null");
        }
        Method method;
        try {
            method = agentBean.getClass().getMethod(
                    CHAT_METHOD, String.class, String.class, InvocationParameters.class);
        } catch (NoSuchMethodException ex) {
            throw new IllegalStateException("Unsupported AGENTIC_SYNC agent bean: "
                    + agentBean.getClass().getName()
                    + ". Expected method chat(String memoryId, String message, InvocationParameters parameters)", ex);
        }
        if (!ResultWithAgenticScope.class.isAssignableFrom(method.getReturnType())) {
            throw new IllegalStateException("AGENTIC_SYNC agent chat method must return ResultWithAgenticScope: "
                    + agentBean.getClass().getName());
        }
        return method;
    }

    public static void validateStartup(Iterable<AgentDefinition> agents) {
        for (AgentDefinition agent : agents) {
            if (agent.runtimeType() != AgentRuntimeType.AGENTIC_SYNC) {
                continue;
            }
            requireChatMethod(agent.agentBean());
        }
    }
}
